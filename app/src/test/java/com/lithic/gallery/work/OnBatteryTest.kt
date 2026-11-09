package com.lithic.gallery.work

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.data.OnBattery
import com.lithic.gallery.data.onBatteryFrom
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** What automatic describing does on battery: which runs wait, which take only the new files, and the setting kept from the switch before it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OnBatteryTest {
    @Test
    fun theChargerIsWaitedForOnlyWhenSoSet() {
        for (rule in OnBattery.entries) {
            val c = Work.automaticConstraints(rule)
            assertWithMessage("$rule: charging").that(c.requiresCharging()).isEqualTo(rule == OnBattery.CHARGER_ONLY)
            assertWithMessage("$rule: battery not low").that(c.requiresBatteryNotLow()).isTrue()
        }
        assertThat(Work.chargerConstraints().requiresCharging()).isTrue()
    }

    @Test
    fun onBatteryNewPhotosOnlyTakesTheNewOnes() {
        // (rule, started by the user, plugged in) -> takes only the new files, stops when unplugged.
        for ((rule, user, plugged) in OnBattery.entries.flatMap { r -> listOf(true, false).flatMap { u -> listOf(true, false).map { p -> Triple(r, u, p) } } }) {
            val what = "$rule, user $user, plugged $plugged"
            val expectNew = rule == OnBattery.NEW_ONLY && !user && !plugged
            val expectStop = rule == OnBattery.NEW_ONLY && !user && plugged
            assertWithMessage("$what: new only").that(Work.newOnly(rule, user, plugged)).isEqualTo(expectNew)
            assertWithMessage("$what: stops unplugged").that(Work.stopsUnplugged(rule, user, plugged)).isEqualTo(expectStop)
        }
        val now = 1_800_000_000_000L
        assertThat(Work.isNew(now - TimeUnit.DAYS.toMillis(7), now)).isTrue()
        assertThat(Work.isNew(now - TimeUnit.DAYS.toMillis(7) - 1, now)).isFalse()
        assertThat(Work.isNew(now, now)).isTrue()
    }

    @Test
    fun theSwitchItReplacedKeepsItsMeaning() {
        assertThat(onBatteryFrom(null, true)).isEqualTo(OnBattery.CHARGER_ONLY)
        assertThat(onBatteryFrom(null, false)).isEqualTo(OnBattery.ANYTIME)
        assertThat(onBatteryFrom(null, null)).isNull()
        assertThat(onBatteryFrom("NEW_ONLY", true)).isEqualTo(OnBattery.NEW_ONLY)
        // A name no longer known falls back to the switch, then the default.
        assertThat(onBatteryFrom("GONE", false)).isEqualTo(OnBattery.ANYTIME)
    }

    @Test
    @Suppress("DEPRECATION") // A sticky broadcast is how the battery's state is set in a test.
    fun pluggedInIsReadFromTheBattery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun battery(plugged: Int) = context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged))
        battery(BatteryManager.BATTERY_PLUGGED_AC)
        assertThat(Work.pluggedIn(context)).isTrue()
        battery(0)
        assertThat(Work.pluggedIn(context)).isFalse()
    }
}
