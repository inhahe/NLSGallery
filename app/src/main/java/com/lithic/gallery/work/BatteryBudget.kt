package com.lithic.gallery.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * The files automatic describing took on battery today, in "New photos only": at most [PER_DAY]
 * a day, so a batch that counts as new (a restore of files without a date taken, which then go
 * by when they were copied) waits for the charger rather than draining the battery. A file is
 * counted once a day, whichever of its passes and runs take it. Kept apart from the settings: it
 * changes with every file, and the settings' readers would all wake up.
 *
 * @param store where they are kept (the app's own, [BatteryBudget] of a context).
 */
class BatteryBudget(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.budget)

    /** The keys of the files counted on [today]: those beyond them may number [PER_DAY] less these. */
    suspend fun charged(today: LocalDate): Set<String> {
        val p = store.data.first()
        return if (p[DAY] == today.toEpochDay()) p[KEYS].orEmpty() else emptySet()
    }

    /** File [key] described (or tried) on battery on [today]. */
    suspend fun use(today: LocalDate, key: String) {
        store.edit { p ->
            val day = today.toEpochDay()
            p[KEYS] = (if (p[DAY] == day) p[KEYS].orEmpty() else emptySet()) + key
            p[DAY] = day
        }
    }

    companion object {
        const val PER_DAY = 100
        private val Context.budget by preferencesDataStore("battery_budget")
        private val DAY = longPreferencesKey("day")
        private val KEYS = stringSetPreferencesKey("keys")
    }
}
