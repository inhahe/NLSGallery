package com.lithic.gallery.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * How many files automatic describing may still take on battery today, in "New photos only":
 * [PER_DAY] a day, so a batch that counts as new (a restore of files without a date taken, which
 * then go by when they were copied) waits for the charger rather than draining the battery.
 * Kept apart from the settings: it changes with every file, and the settings' readers would
 * all wake up.
 *
 * @param store where the count is kept (the app's own, [BatteryBudget] of a context).
 */
class BatteryBudget(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.budget)

    /** Files still allowed on [today]. */
    suspend fun left(today: LocalDate): Int {
        val p = store.data.first()
        val used = if (p[DAY] == today.toEpochDay()) p[USED] ?: 0 else 0
        return (PER_DAY - used).coerceAtLeast(0)
    }

    /** One more file described (or tried) on battery on [today]. */
    suspend fun use(today: LocalDate) {
        store.edit { p ->
            val day = today.toEpochDay()
            p[USED] = if (p[DAY] == day) (p[USED] ?: 0) + 1 else 1
            p[DAY] = day
        }
    }

    companion object {
        const val PER_DAY = 100
        private val Context.budget by preferencesDataStore("battery_budget")
        private val DAY = longPreferencesKey("day")
        private val USED = intPreferencesKey("used")
    }
}
