package com.lithic.gallery.search

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lithic.gallery.core.places.Gazetteer
import com.lithic.gallery.core.search.PlacePhrases
import com.lithic.gallery.data.GalleryDatabase
import com.lithic.gallery.data.LocationEntity
import com.lithic.gallery.data.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The library's places, from the locations read, by the names they go by. (The files are not
 * on the device, so a scan's reads fail and write nothing: it builds from the rows put there.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlacesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: GalleryDatabase
    private var gazetteerReads = 0
    private val gazetteer by lazy { Gazetteer(context.assets.open("places.bin").use { it.readBytes() }) }
    private lateinit var places: Places
    private var nextId = 1L

    @Before
    fun open() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).allowMainThreadQueries().build()
        places = Places(context, db.media()) { gazetteerReads++; gazetteer }
    }

    @After
    fun close() = db.close()

    /** A file of the library with AI key [key], taken at ([lat], [lon]) (nowhere when null). */
    private suspend fun file(key: String, lat: Double?, lon: Double?): MediaEntity {
        val id = nextId++
        val m = MediaEntity(id, "content://media/external/images/media/$id", false, "IMG_$id.jpg", "DCIM/Camera/", "image/jpeg",
            id, id, 1, 4, 3, 0, 0, 0, key, 0)
        db.media().upsert(listOf(m))
        db.media().upsertLocations(listOf(LocationEntity(key, lat, lon)))
        return m
    }

    @Test
    fun placesAreFoundByEveryNameTheyGoBy() = runBlocking {
        file("waikiki", 21.2766, -157.8253)
        file("eiffel", 48.8584, 2.2945)
        file("texas", 33.6609, -95.5555) // Paris, Texas
        file("sea", 0.0, -140.0) // far from any place
        file("nowhere", null, null)
        places.scan()
        val s = places.snapshot.value

        assertThat(s.placeOf.keys).containsExactly("waikiki", "eiffel", "texas")
        // Four say where they were taken, one of them nowhere near a place.
        assertThat(s.located).isEqualTo(4)
        assertThat(s.keys.getValue("hawaii")).containsExactly("waikiki")
        assertThat(s.keys.getValue("honolulu")).containsExactly("waikiki")
        assertThat(s.names.getValue("hawaii")).isEqualTo(PlacePhrases.Known("Hawaii, United States"))
        // A country by the names GeoNames does not use, for the files from there.
        assertThat(s.keys.getValue("usa")).containsExactly("waikiki", "texas")
        assertThat(s.names.getValue("usa")).isEqualTo(PlacePhrases.Known("United States", country = true))
        assertThat(s.names.getValue("united states").country).isTrue()
        assertThat(s.keys).doesNotContainKey("uk")
        // Two places of one name: both found by it, which reads as the name alone.
        assertThat(s.keys.getValue("paris")).containsExactly("eiffel", "texas")
        assertThat(s.names.getValue("paris").label).isEqualTo("Paris")
        assertThat(s.names.getValue("ile de france").label).isEqualTo("Île-de-France, France")
    }

    @Test
    fun aCountrysOtherNameThatIsATownsToo() = runBlocking {
        file("holland mi", 42.7875, -86.1089) // Holland, Michigan
        file("amsterdam", 52.3676, 4.9041)
        places.scan()
        val s = places.snapshot.value
        assertThat(s.keys.getValue("holland")).containsExactly("holland mi", "amsterdam")
        assertThat(s.names.getValue("holland").label).isEqualTo("Holland")
    }

    @Test
    fun filesGoneTakeTheirPlacesWithThem() = runBlocking {
        val paris = file("eiffel", 48.8584, 2.2945)
        file("waikiki", 21.2766, -157.8253)
        places.scan()
        assertThat(places.snapshot.value.keys).containsKey("paris")

        db.media().delete(listOf(paris.id))
        places.scan()
        assertThat(places.snapshot.value.keys).doesNotContainKey("paris")
        assertThat(places.snapshot.value.placeOf.keys).containsExactly("waikiki")
        // Unchanged, the snapshot is not sent again (an open search would be run again).
        val before = places.snapshot.value
        places.scan()
        assertThat(places.snapshot.value).isSameInstanceAs(before)
    }

    @Test
    fun aFileThatCouldNotBeReadIsReadAgain(): Unit = runBlocking {
        // A file of the library whose location was never read: here its read fails (no such file).
        val id = nextId++
        db.media().upsert(listOf(MediaEntity(id, "content://media/external/images/media/$id", false, "IMG_$id.jpg",
            "DCIM/Camera/", "image/jpeg", id, id, 1, 4, 3, 0, 0, 0, "unread", 0)))
        places.scan()
        assertThat(db.media().withoutLocation().map { it.aiKey }).containsExactly("unread")
    }

    @Test
    fun locationsAreForgottenWhenThePermissionIsTakenBack() = runBlocking {
        file("waikiki", 21.2766, -157.8253)
        places.scan()
        assertThat(places.snapshot.value.placeOf.keys).containsExactly("waikiki")

        shadowOf(context as Application).denyPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        places.scan()
        assertThat(places.snapshot.value.placeOf).isEmpty()
        assertThat(places.snapshot.value.keys).isEmpty()
        assertThat(db.media().locations()).isEmpty()
    }

    @Test
    fun theGazetteerIsReadOnlyForALibraryThatSaysWhere() = runBlocking {
        file("nowhere", null, null)
        places.scan()
        assertThat(gazetteerReads).isEqualTo(0)
        assertThat(places.snapshot.value.keys).isEmpty()
    }
}
