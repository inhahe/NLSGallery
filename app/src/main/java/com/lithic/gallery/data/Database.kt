package com.lithic.gallery.data

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
 * A null part has not been made yet (it was switched off when the file was described).
 */
@Entity(tableName = "ai_descriptions")
data class DescriptionEntity(
    @PrimaryKey val hash: String,
    /** BLIP's caption; for a video, the captions of its sampled frames joined with "; ". */
    val caption: String?,
    /** EasyOCR's text. */
    val ocr: String?,
    /** The file's own caption (EXIF ImageDescription / UserComment / XMP dc:description). */
    val manualCaption: String,
    val describedAt: Long,
    /** Why describing failed, if it did (the file is not retried until it changes). */
    val error: String?,
)

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

    @Query("SELECT id, generation, dateModified, size FROM media")
    suspend fun syncState(): List<SyncState>

    @Upsert
    suspend fun upsert(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM media")
    suspend fun count(): Int

    @Query("SELECT * FROM display_aspect")
    fun observeAspects(): Flow<List<DisplayAspectEntity>>

    @Upsert
    suspend fun upsertAspect(a: DisplayAspectEntity)
}

data class SyncState(val id: Long, val generation: Long, val dateModified: Long, val size: Long)

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

    /**
     * Files still to describe, newest first: no description, or a part that is wanted and missing.
     * Failed files are skipped (they are retried when they change, which changes their key).
     */
    @Query(
        """SELECT m.* FROM media m LEFT JOIN ai_descriptions d ON d.hash = m.aiKey
           WHERE (m.isVideo = 0 OR :videos)
             AND (d.hash IS NULL OR (d.error IS NULL AND
                  ((:captions AND d.caption IS NULL) OR (:ocr AND d.ocr IS NULL))))
           ORDER BY m.sortDate DESC, m.id DESC"""
    )
    suspend fun undescribed(videos: Boolean, captions: Boolean, ocr: Boolean): List<MediaEntity>

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

    @Query("DELETE FROM ai_descriptions WHERE error IS NOT NULL")
    suspend fun clearFailures()

    /** Changes whenever a description is added or replaced (to refresh the search index). */
    @Query("SELECT COUNT(*) * 1000003 + COALESCE(MAX(describedAt), 0) FROM ai_descriptions")
    fun observeVersion(): Flow<Long>

    @Query("SELECT COUNT(*) FROM ai_descriptions WHERE error IS NOT NULL")
    fun observeFailureCount(): Flow<Int>

    @Query("DELETE FROM ai_descriptions WHERE hash = :hash")
    suspend fun deleteDescription(hash: String)

    @Query("DELETE FROM ai_embeddings WHERE hash = :hash")
    suspend fun deleteEmbeddings(hash: String)
}

@Database(
    entities = [MediaEntity::class, DescriptionEntity::class, EmbeddingEntity::class, DisplayAspectEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun ai(): AiDao

    companion object {
        fun open(context: Context): GalleryDatabase =
            Room.databaseBuilder(context, GalleryDatabase::class.java, "gallery.db")
                .addMigrations(MIGRATION_1_2)
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
    }
}
