package com.lithic.gallery.data

import android.content.Context
import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One photo or video, as MediaStore reports it. The app's copy lets the gallery open instantly
 * and joins each file to its AI description.
 */
@Entity(tableName = "media", indices = [Index("sortDate"), Index("aiKey")])
data class MediaEntity(
    /** MediaStore's _ID (unique across images and videos). */
    @PrimaryKey val id: Long,
    /** The content URI to open it with. */
    val uri: String,
    val isVideo: Boolean,
    val displayName: String,
    /** Folder within its volume, e.g. "DCIM/Camera/". */
    val relativePath: String,
    val mimeType: String,
    /** When it was taken (EXIF/metadata), else when last modified, in milliseconds. Newest first. */
    val sortDate: Long,
    /** File modification time, seconds (MediaStore's DATE_MODIFIED). */
    val dateModified: Long,
    val size: Long,
    /** Width and height as displayed, i.e. after its rotation is applied. 0 when unknown. */
    val width: Int,
    val height: Int,
    /** Degrees clockwise the stored pixels are turned for display. */
    val orientation: Int,
    val durationMs: Long,
    /** [Folder] ordinal. */
    val folder: Int,
    /** Key of its AI description: sha1(name, size, mtime), so a moved file keeps it (as thumbsup2's does). */
    val aiKey: String,
    /** MediaStore's GENERATION_MODIFIED (API 30+), 0 before. */
    val generation: Long,
) {
    val contentUri: Uri get() = Uri.parse(uri)
    /** Displayed width / height; 1 when unknown. */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
    val folderCategory: Folder get() = Folder.entries[folder]
}

/**
 * What the AI said about a file's contents: thumbsup2's `ai_descriptions` row, keyed the same way.
 * A null part has not been made yet: its pass has not reached the file, or it was switched off.
 */
@Entity(tableName = "ai_descriptions")
data class DescriptionEntity(
    @PrimaryKey val hash: String,
    /** BLIP's caption; for a video, the captions of its sampled frames joined with "; ". */
    val caption: String?,
    /** EasyOCR's text. */
    val ocr: String?,
    /**
     * The file's own caption, "" for none: a photo's EXIF ImageDescription, else its XMP
     * dc:description, dc:title or xmp:Label (thumbsup2's order); a video's title.
     */
    val manualCaption: String,
    val describedAt: Long,
    /**
     * Why the file could not be described, if so: it could not be read, or the models failed on
     * it [MAX_MODEL_FAILURES] times. It is not tried again until it changes (a new key) or the
     * failures are retried.
     */
    val error: String?,
    /**
     * Runs in which the caption or text models failed on it while they were seen to work on other
     * input (another file, or a probe), since a part of it was last made. MiniLM's failures are not
     * counted: retrying an embedding costs milliseconds.
     */
    @ColumnInfo(defaultValue = "0") val modelFailures: Int = 0,
    /**
     * When the models last failed on it, counted or not; null since a part was last made. Runs
     * take such files last, so files the models fail on cannot hold up the rest.
     */
    val lastModelFailure: Long? = null,
) {
    companion object {
        /** Model failures after which a file is set aside as failed, so it stops costing every run. */
        const val MAX_MODEL_FAILURES = 3
    }
}

/** A MiniLM embedding of a description's caption + OCR text, per model (thumbsup2's ai_embeddings). */
@Entity(tableName = "ai_embeddings", primaryKeys = ["hash", "model"])
data class EmbeddingEntity(
    val hash: String,
    val model: String,
    /** 384 float32s, little-endian. */
    val vector: ByteArray,
    /** Hash of the text embedded, to know when caption or OCR changed under it. */
    val textHash: String,
)

/**
 * A file's displayed aspect ratio where MediaStore's size gives the wrong one (a video with
 * non-square pixels): learned from its decoded thumbnail, valid while the file is unchanged.
 */
@Entity(tableName = "display_aspect")
data class DisplayAspectEntity(
    @PrimaryKey val id: Long,
    val dateModified: Long,
    /** Width / height as displayed. */
    val aspect: Float,
)

/**
 * What says whether a file's searchable text may have changed, cheaply: the fields of its own
 * that are searched, when its description was last written, which text its embedding is of.
 */
data class SearchSignature(
    val id: Long,
    val aiKey: String,
    val sortDate: Long,
    val displayName: String,
    val relativePath: String,
    val describedAt: Long?,
    val textHash: String?,
)

/**
 * Where a file was taken, from its EXIF GPS or a video's location, by its AI key (so a moved file
 * keeps it): [lat] and [lon] in degrees, both null when it says nowhere (or the phone withholds it).
 * A file with no row has not been read yet.
 */
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val hash: String,
    val lat: Double?,
    val lon: Double?,
)

/** A media item joined to its description (null parts: not described). */
data class MediaWithDescription(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val sortDate: Long,
    val isVideo: Boolean,
    val aiKey: String,
    val caption: String?,
    val ocr: String?,
    val manualCaption: String?,
)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY sortDate DESC, id DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media ORDER BY sortDate DESC, id DESC")
    suspend fun all(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: Long): MediaEntity?

    @Upsert
    suspend fun upsert(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM display_aspect")
    fun observeAspects(): Flow<List<DisplayAspectEntity>>

    @Upsert
    suspend fun upsertAspect(a: DisplayAspectEntity)

    /** Files whose location has not been read ([LocationEntity]), newest first. */
    @Query(
        """SELECT m.* FROM media m LEFT JOIN locations l ON l.hash = m.aiKey
           WHERE l.hash IS NULL ORDER BY m.sortDate DESC, m.id DESC"""
    )
    suspend fun withoutLocation(): List<MediaEntity>

    @Upsert
    suspend fun upsertLocations(list: List<LocationEntity>)

    /** Forgets every location read (the permission to read them was taken back); how many there were. */
    @Query("DELETE FROM locations")
    suspend fun forgetLocations(): Int

    /** Where the library's files were taken: every location read that says somewhere, of a file still there. */
    @Query(
        """SELECT * FROM locations l WHERE l.lat IS NOT NULL AND l.lon IS NOT NULL
           AND EXISTS (SELECT 1 FROM media m WHERE m.aiKey = l.hash)"""
    )
    suspend fun locations(): List<LocationEntity>
}

@Dao
interface AiDao {
    @Query("SELECT * FROM ai_descriptions WHERE hash = :hash")
    suspend fun description(hash: String): DescriptionEntity?

    @Query("SELECT * FROM ai_descriptions WHERE hash = :hash")
    fun observeDescription(hash: String): Flow<DescriptionEntity?>

    @Upsert
    suspend fun upsertDescription(d: DescriptionEntity)

    @Upsert
    suspend fun upsertEmbedding(e: EmbeddingEntity)

    @Query("SELECT * FROM ai_embeddings WHERE hash = :hash AND model = :model")
    suspend fun embedding(hash: String, model: String): EmbeddingEntity?

    @Query("SELECT * FROM ai_embeddings WHERE model = :model")
    suspend fun embeddings(model: String): List<EmbeddingEntity>

    /** The embeddings of descriptions [hashes] (at most 900 at a time: SQLite's variable limit). */
    @Query("SELECT * FROM ai_embeddings WHERE model = :model AND hash IN (:hashes)")
    suspend fun embeddings(model: String, hashes: List<String>): List<EmbeddingEntity>

    /**
     * A description with the embedding of its text ([e], null when it has none or the stored one
     * is current), in one transaction: a reader sees both or neither.
     */
    @Transaction
    suspend fun saveDescribed(d: DescriptionEntity, e: EmbeddingEntity?) {
        upsertDescription(d)
        if (e != null) upsertEmbedding(e)
    }

    /**
     * A description whose embedding could not be made: saved, and any older embedding (of other
     * text) removed with it, so [withoutEmbedding] lists it.
     */
    @Transaction
    suspend fun saveUnembedded(d: DescriptionEntity) {
        upsertDescription(d)
        deleteEmbeddings(d.hash)
    }

    /**
     * Descriptions of files in the library with text but no [model] embedding (MiniLM failed when
     * they were saved). The text test here is a cheap first cut (SQLite trims only spaces); the
     * caller applies the exact one.
     */
    @Query(
        """SELECT d.* FROM ai_descriptions d
           LEFT JOIN ai_embeddings e ON e.hash = d.hash AND e.model = :model
           WHERE d.error IS NULL AND e.hash IS NULL
             AND EXISTS (SELECT 1 FROM media m WHERE m.aiKey = d.hash)
             AND (TRIM(COALESCE(d.caption, '')) != '' OR TRIM(COALESCE(d.ocr, '')) != '')"""
    )
    suspend fun withoutEmbedding(model: String): List<DescriptionEntity>

    /**
     * Files whose caption the models made, photos first, the latest first: what the caption models
     * are tried on again to see whether they still work.
     */
    @Query(
        """SELECT m.* FROM media m JOIN ai_descriptions d ON d.hash = m.aiKey
           WHERE d.caption IS NOT NULL AND d.error IS NULL
           ORDER BY m.isVideo, d.describedAt DESC LIMIT :limit"""
    )
    suspend fun captionedReferences(limit: Int): List<MediaEntity>

    /** Files whose text the models read, photos first, the latest first: the text models' references. */
    @Query(
        """SELECT m.* FROM media m JOIN ai_descriptions d ON d.hash = m.aiKey
           WHERE d.ocr IS NOT NULL AND d.error IS NULL
           ORDER BY m.isVideo, d.describedAt DESC LIMIT :limit"""
    )
    suspend fun readReferences(limit: Int): List<MediaEntity>

    /**
     * Embedding [e] saved only if its description is still the text [of] it was made from (not
     * reset, failed or described again meanwhile), with a new describedAt [at] so the search index
     * sees it. Whether it was saved.
     */
    @Transaction
    suspend fun saveEmbeddingIfCurrent(e: EmbeddingEntity, of: DescriptionEntity, at: Long): Boolean {
        val now = description(e.hash) ?: return false
        if (now.error != null || now.caption != of.caption || now.ocr != of.ocr) return false
        upsertEmbedding(e)
        upsertDescription(now.copy(describedAt = at))
        return true
    }

    /**
     * The models failed on file [hash] at [at], whoever's fault it was; [manual] is its own
     * caption, read before they ran, kept so it is searched meanwhile.
     */
    @Transaction
    suspend fun noteModelFailure(hash: String, manual: String, at: Long) {
        val d = description(hash)
        if (d?.error != null) return
        upsertDescription(
            d?.copy(manualCaption = manual, describedAt = at, lastModelFailure = at)
                ?: DescriptionEntity(hash, null, null, manual, at, null, 0, at)
        )
    }

    /**
     * Counts a run in which the models failed on file [hash] while they were seen to work on other
     * input; the [max]th sets it aside as failed with [message]. Only while the failure is current
     * (no part made, nor the description reset, since it was noted). Whether it was counted.
     */
    @Transaction
    suspend fun countModelFailure(hash: String, message: String, at: Long, max: Int): Boolean {
        val d = description(hash) ?: return false
        if (d.error != null || d.lastModelFailure == null) return false
        val n = d.modelFailures + 1
        val error = if (n >= max) "the AI failed on it $n times: $message" else null
        upsertDescription(d.copy(describedAt = at, error = error, modelFailures = n))
        return true
    }

    /**
     * Files still to describe, newest first: no description, or a part that is wanted and missing.
     * Failed files are skipped (they are retried when they change, which changes their key).
     */
    @Query(
        """SELECT m.* FROM media m LEFT JOIN ai_descriptions d ON d.hash = m.aiKey
           WHERE (m.isVideo = 0 OR :videos)
             AND (d.hash IS NULL OR (d.error IS NULL AND
                  ((:captions AND d.caption IS NULL) OR (:ocr AND d.ocr IS NULL))))
           ORDER BY d.lastModelFailure IS NOT NULL, m.sortDate DESC, m.id DESC"""
    )
    suspend fun undescribed(videos: Boolean, captions: Boolean, ocr: Boolean): List<MediaEntity>

    /**
     * Files whose text is still to be read, likeliest to hold text first (screenshots, downloads,
     * messages, other, camera), newest first within each.
     */
    @Query(
        """SELECT m.* FROM media m LEFT JOIN ai_descriptions d ON d.hash = m.aiKey
           WHERE (m.isVideo = 0 OR :videos) AND (d.hash IS NULL OR (d.error IS NULL AND d.ocr IS NULL))
           ORDER BY d.lastModelFailure IS NOT NULL,
                    CASE m.folder WHEN 1 THEN 0 WHEN 3 THEN 1 WHEN 2 THEN 2 WHEN 4 THEN 3 ELSE 4 END,
                    m.sortDate DESC, m.id DESC"""
    )
    suspend fun withoutText(videos: Boolean): List<MediaEntity>

    @Query(
        """SELECT COUNT(*) FROM media m LEFT JOIN ai_descriptions d ON d.hash = m.aiKey
           WHERE (m.isVideo = 0 OR :videos)
             AND (d.hash IS NULL OR (d.error IS NULL AND
                  ((:captions AND d.caption IS NULL) OR (:ocr AND d.ocr IS NULL))))"""
    )
    fun observeUndescribedCount(videos: Boolean, captions: Boolean, ocr: Boolean): Flow<Int>

    @Query(
        """SELECT m.id, m.displayName, m.relativePath, m.sortDate, m.isVideo, m.aiKey,
                  d.caption, d.ocr, d.manualCaption
           FROM media m LEFT JOIN ai_descriptions d ON d.hash = m.aiKey"""
    )
    suspend fun searchable(): List<MediaWithDescription>

    /** [searchable] for media [ids] (at most 900 at a time). */
    @Query(
        """SELECT m.id, m.displayName, m.relativePath, m.sortDate, m.isVideo, m.aiKey,
                  d.caption, d.ocr, d.manualCaption
           FROM media m LEFT JOIN ai_descriptions d ON d.hash = m.aiKey WHERE m.id IN (:ids)"""
    )
    suspend fun searchable(ids: List<Long>): List<MediaWithDescription>

    /** Every file's [SearchSignature], with the text hash of its [model] embedding. */
    @Query(
        """SELECT m.id, m.aiKey, m.sortDate, m.displayName, m.relativePath, d.describedAt, e.textHash
           FROM media m LEFT JOIN ai_descriptions d ON d.hash = m.aiKey
           LEFT JOIN ai_embeddings e ON e.hash = m.aiKey AND e.model = :model"""
    )
    suspend fun searchSignatures(model: String): List<SearchSignature>

    @Query("DELETE FROM ai_descriptions WHERE error IS NOT NULL")
    suspend fun deleteFailures()

    @Query("DELETE FROM ai_embeddings WHERE hash IN (SELECT hash FROM ai_descriptions WHERE error IS NOT NULL)")
    suspend fun deleteEmbeddingsOfFailures()

    /** Failed files, with whatever embedding they had, forgotten in one transaction, to be tried again. */
    @Transaction
    suspend fun clearFailures() {
        deleteEmbeddingsOfFailures()
        deleteFailures()
    }

    /** Changes whenever a description is added or replaced (to refresh the search index). */
    @Query("SELECT COUNT(*) * 1000003 + COALESCE(MAX(describedAt), 0) FROM ai_descriptions")
    fun observeVersion(): Flow<Long>

    @Query("SELECT COUNT(*) FROM ai_descriptions WHERE error IS NOT NULL")
    fun observeFailureCount(): Flow<Int>

    @Query("DELETE FROM ai_descriptions WHERE hash = :hash")
    suspend fun deleteDescription(hash: String)

    @Query("DELETE FROM ai_embeddings WHERE hash = :hash")
    suspend fun deleteEmbeddings(hash: String)

    /** Description [hash] and its embeddings, gone in one transaction, to be made again. */
    @Transaction
    suspend fun forget(hash: String) {
        deleteDescription(hash)
        deleteEmbeddings(hash)
    }
}

@Database(
    entities = [MediaEntity::class, DescriptionEntity::class, EmbeddingEntity::class, DisplayAspectEntity::class, LocationEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun ai(): AiDao

    companion object {
        fun open(context: Context): GalleryDatabase =
            Room.databaseBuilder(context, GalleryDatabase::class.java, "gallery.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        /** 2 learns displayed aspect ratios from thumbnails ([DisplayAspectEntity]). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `display_aspect` (`id` INTEGER NOT NULL, " +
                        "`dateModified` INTEGER NOT NULL, `aspect` REAL NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /** 3 counts the runs the models failed on a file ([DescriptionEntity.modelFailures]). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ai_descriptions` ADD COLUMN `modelFailures` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 4 notes when the models last failed on a file ([DescriptionEntity.lastModelFailure]). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ai_descriptions` ADD COLUMN `lastModelFailure` INTEGER")
            }
        }

        /** 5 keeps where each file was taken ([LocationEntity]). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `locations` (`hash` TEXT NOT NULL, `lat` REAL, `lon` REAL, PRIMARY KEY(`hash`))"
                )
            }
        }
    }
}
