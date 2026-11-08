package com.lithic.gallery.data

import com.google.common.truth.Truth.assertThat
import com.lithic.gallery.core.search.HybridSearch
import com.lithic.gallery.search.SearchService
import org.junit.Test

/** The search fields chosen survive fields being added: the new ones start out searched, the rest as chosen. */
class SettingsTest {
    @Test
    fun aFieldAddedSinceTheChoiceStartsOutSearched() {
        // Saved before the place field (and before the fields known were kept): place is added.
        assertThat(searchFieldsFrom("${HybridSearch.FILENAME},${HybridSearch.PATH}", null))
            .containsExactly(HybridSearch.FILENAME, HybridSearch.PATH, SearchService.PLACE)
        // Saved since, with place turned off: it stays off.
        assertThat(searchFieldsFrom(HybridSearch.FILENAME, SearchService.FIELDS.joinToString(",")))
            .containsExactly(HybridSearch.FILENAME)
        // A field no longer searched is dropped.
        assertThat(searchFieldsFrom("gone,${HybridSearch.AI_CAPTION}", SearchService.FIELDS.joinToString(",")))
            .containsExactly(HybridSearch.AI_CAPTION)
        assertThat(searchFieldsFrom(null, null)).isNull()
    }
}
