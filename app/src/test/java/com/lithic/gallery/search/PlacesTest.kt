package com.lithic.gallery.search

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.lithic.gallery.core.places.Gazetteer
import com.lithic.gallery.data.GalleryDatabase
import com.lithic.gallery.data.LocationEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The library's places, from the locations read, by the names they go by. (The library is empty,
 * so a scan reads no files: it builds from the rows put there.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PlacesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: GalleryDatabase
    private var gazetteerReads = 0
    private val gazetteer by lazy { Gazetteer(context.assets.open("places.bin").use { it.readBytes() }) }
    private lateinit var places: Places

    @Before
    fun open() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).allowMainThreadQueries().build()
        places = Places(context, db.media()) { gazetteerReads++; gazetteer }
    }

    @After
    fun close() = db.close()

    @Test
    fun placesAreFoundByEveryNameTheyGoBy() = runBlocking {
        db.media().upsertLocations(listOf(
            LocationEntity("waikiki", 21.2766, -157.8253),
            LocationEntity("eiffel", 48.8584, 2.2945),
            LocationEntity("texas", 33.6609, -95.5555), // Paris, Texas
            LocationEntity("sea", 0.0, -140.0), // far from any place
            LocationEntity("nowhere", null, null),
        ))
        places.scan()
        val s = places.snapshot.value

        assertThat(s.placeOf.keys).containsExactly("waikiki", "eiffel", "texas")
        assertThat(s.keys.getValue("hawaii")).containsExactly("waikiki")
        assertThat(s.keys.getValue("honolulu")).containsExactly("waikiki")
        assertThat(s.labels.getValue("hawaii")).isEqualTo("Hawaii, United States")
        // A country by the names GeoNames does not use, for the files from there.
        assertThat(s.keys.getValue("usa")).containsExactly("waikiki", "texas")
        assertThat(s.labels.getValue("usa")).isEqualTo("United States")
        assertThat(s.keys).doesNotContainKey("uk")
        // Two places of one name: both found by it, which reads as the name alone.
        assertThat(s.keys.getValue("paris")).containsExactly("eiffel", "texas")
        assertThat(s.labels.getValue("paris")).isEqualTo("Paris")
        assertThat(s.labels.getValue("ile de france")).isEqualTo("Île-de-France, France")
    }

    @Test
    fun locationsAreForgottenWhenThePermissionIsTakenBack() = runBlocking {
        db.media().upsertLocations(listOf(LocationEntity("waikiki", 21.2766, -157.8253)))
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
        db.media().upsertLocations(listOf(LocationEntity("nowhere", null, null)))
        places.scan()
        assertThat(gazetteerReads).isEqualTo(0)
        assertThat(places.snapshot.value.keys).isEmpty()
    }
}
