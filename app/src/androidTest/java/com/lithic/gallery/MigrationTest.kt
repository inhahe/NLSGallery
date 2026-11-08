package com.lithic.gallery

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lithic.gallery.data.GalleryDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every database version the app has shipped migrates to the current one with its descriptions
 * kept (the schema checked against the entities), so an update never costs a re-describe.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)

    private val name = "migration-test.db"

    private fun insertDescription(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ai_descriptions (hash, caption, ocr, manualCaption, describedAt, error) " +
                "VALUES ('k', 'a cat on a table', 'SALE', '', 5, NULL)"
        )
    }

    private fun assertKept(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT caption, ocr, modelFailures, lastModelFailure FROM ai_descriptions WHERE hash = 'k'").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("a cat on a table")
            assertThat(c.getString(1)).isEqualTo("SALE")
            assertThat(c.getInt(2)).isEqualTo(0)
            assertThat(c.isNull(3)).isTrue()
        }
    }

    private val all = arrayOf(GalleryDatabase.MIGRATION_1_2, GalleryDatabase.MIGRATION_2_3, GalleryDatabase.MIGRATION_3_4)

    @Test
    fun fromVersion1() {
        helper.createDatabase(name, 1).use(::insertDescription)
        helper.runMigrationsAndValidate(name, 4, true, *all).use(::assertKept)
    }

    @Test
    fun fromVersion2() {
        helper.createDatabase(name, 2).use(::insertDescription)
        helper.runMigrationsAndValidate(name, 4, true, *all).use(::assertKept)
    }

    @Test
    fun fromVersion3() {
        helper.createDatabase(name, 3).use(::insertDescription)
        helper.runMigrationsAndValidate(name, 4, true, *all).use(::assertKept)
    }
}
