package com.lithic.gallery.work

import android.content.Context
import android.os.BatteryManager
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.util.concurrent.ListenableFuture
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.OnBattery
import com.lithic.gallery.data.onBatteryFrom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * What automatic describing does on battery, and how requests for describe runs are placed:
 * against WorkManager itself (its test build), with workers that hold until a test ends them,
 * so a run can be kept going while requests arrive.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DescribeSchedulingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var wm: WorkManager

    /** The workers started and not yet ended by the test, by request id. */
    private val going = HashMap<UUID, CallbackToFutureAdapter.Completer<ListenableWorker.Result>>()

    /** The tags of every worker started, in order. */
    private val started = mutableListOf<Set<String>>()

    private inner class Holding(c: Context, p: WorkerParameters) : ListenableWorker(c, p) {
        override fun startWork(): ListenableFuture<Result> = CallbackToFutureAdapter.getFuture { done ->
            going[id] = done
            started.add(tags)
            "held"
        }
    }

    @Before
    fun setUp() {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    Holding(appContext, workerParameters)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        wm = WorkManager.getInstance(context)
        charging(true)
    }

    // The describe chain, as runs going and runs waiting, by their kind.
    private fun live(): List<WorkInfo> = wm.getWorkInfosForUniqueWork(Work.DESCRIBE).get().filter { !it.state.isFinished }
    private fun kind(info: WorkInfo): Run = Run.entries.single { it.tag in info.tags }
    private fun running(): List<WorkInfo> = live().filter { it.state == WorkInfo.State.RUNNING }
    private fun waiting(): List<WorkInfo> = live().filter { it.state != WorkInfo.State.RUNNING }
    private fun request(kind: Run) = runBlocking { Work.request(context, kind) }
    private fun chargerJobQueued() = wm.getWorkInfosForUniqueWork(Work.DESCRIBE_ON_CHARGER).get().any { !it.state.isFinished }

    /** Starts the automatic run waiting, as Android would once its constraints are met. */
    private fun startWaiting() {
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(waiting().single().id)
    }

    /** Ends the run going, successfully. */
    private fun endRunning() {
        val id = running().single().id
        going.remove(id)!!.set(ListenableWorker.Result.success())
    }

    private fun charging(on: Boolean) {
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIsCharging(on)
    }

    @Test
    fun runsWaitForWhatTheirKindSays() {
        request(Work.automatic(OnBattery.NEW_ONLY))
        val battery = waiting().single()
        assertThat(kind(battery)).isEqualTo(Run.BATTERY)
        assertThat(battery.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(battery.constraints.requiresCharging()).isFalse()
        assertThat(Work.automatic(OnBattery.ANYTIME)).isEqualTo(Run.BATTERY)

        Work.stopDescribing(context)
        request(Work.automatic(OnBattery.CHARGER_ONLY))
        val charger = waiting().single()
        assertThat(kind(charger)).isEqualTo(Run.CHARGER)
        assertThat(charger.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(charger.constraints.requiresCharging()).isTrue()

        // The user's starts at once, whatever the battery.
        Work.stopDescribing(context)
        request(Run.USER)
        assertThat(running().map(::kind)).containsExactly(Run.USER)
        assertThat(running().single().constraints).isEqualTo(Constraints.NONE)
    }

    @Test
    fun aUserRequestIsNeverTakenForLess() {
        request(Run.USER)
        request(Run.BATTERY)
        assertThat(running().map(::kind)).containsExactly(Run.USER)
        assertThat(waiting().map(::kind)).containsExactly(Run.BATTERY)
        // The user asks again (redescribing a file the run going has not got): the run waiting
        // becomes theirs, at once and whatever the battery, rather than the request being dropped.
        request(Run.USER)
        val mine = waiting().single()
        assertThat(kind(mine)).isEqualTo(Run.USER)
        assertThat(mine.constraints).isEqualTo(Constraints.NONE)
        // Automatic requests after it leave it the user's, and still one waiting.
        request(Run.BATTERY)
        request(Run.CHARGER)
        assertThat(waiting().map { it.id to kind(it) }).containsExactly(mine.id to Run.USER)
        // It starts as the one going ends.
        endRunning()
        assertThat(running().single().id).isEqualTo(mine.id)
        assertThat(started.last()).contains(Run.USER.tag)
    }

    @Test
    fun withNoneGoingWhatWaitsIsReplacedByTheMergedRequest() {
        request(Run.CHARGER)
        assertThat(waiting().map(::kind)).containsExactly(Run.CHARGER)
        // New files on battery do not wait behind the charger: the run on battery queues the
        // charger's again for what it leaves.
        request(Run.BATTERY)
        assertThat(waiting().map(::kind)).containsExactly(Run.BATTERY)
        request(Run.CHARGER)
        assertThat(waiting().map(::kind)).containsExactly(Run.BATTERY)
        request(Run.USER)
        assertThat(waiting()).isEmpty()
        assertThat(running().map(::kind)).containsExactly(Run.USER)
    }

    @Test
    fun stopStopsTheChargersRunToo() {
        Work.describeOnCharger(context)
        request(Run.USER)
        request(Run.BATTERY)
        assertThat(chargerJobQueued()).isTrue()
        Work.stopDescribing(context)
        assertThat(live()).isEmpty()
        assertThat(chargerJobQueued()).isFalse()
    }

    @Test
    fun aRunOnTheChargerUnpluggedGivesWayToOneOnBattery() {
        request(Run.CHARGER)
        startWaiting()
        val charger = running().single().id
        request(Run.BATTERY)
        assertThat(waiting().single().state).isEqualTo(WorkInfo.State.BLOCKED)

        // Stopped while still charging (Android's time limit, say): Android queues it again.
        runBlocking { Work.chargerRunStopped(context, charger, OnBattery.NEW_ONLY) }
        assertThat(running().single().id).isEqualTo(charger)
        // In "Nothing" it waits for the charger, unplugged too.
        charging(false)
        runBlocking { Work.chargerRunStopped(context, charger, OnBattery.CHARGER_ONLY) }
        assertThat(running().single().id).isEqualTo(charger)

        // Unplugged in "New photos only": a run on battery in its place, not blocked behind it.
        runBlocking { Work.chargerRunStopped(context, charger, OnBattery.NEW_ONLY) }
        val battery = live().single()
        assertThat(battery.id).isNotEqualTo(charger)
        assertThat(kind(battery)).isEqualTo(Run.BATTERY)
        assertThat(battery.state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun aRunOnTheChargerStoppedByTheUserStaysStopped() {
        request(Run.CHARGER)
        startWaiting()
        val charger = running().single().id
        charging(false)
        Work.stopDescribing(context)
        runBlocking { Work.chargerRunStopped(context, charger, OnBattery.NEW_ONLY) }
        assertThat(live()).isEmpty()
    }

    @Test
    fun runsArePlannedAgainWhenTheRuleChanges() {
        // A run on battery going took what the old rule allowed: it starts again, by the new one.
        request(Work.automatic(OnBattery.NEW_ONLY))
        startWaiting()
        val old = running().single().id
        runBlocking { Work.replan(context, OnBattery.CHARGER_ONLY) }
        assertThat(live().map { Triple(it.id == old, it.state, kind(it)) })
            .containsExactly(Triple(false, WorkInfo.State.ENQUEUED, Run.CHARGER))

        // The user's going stays; the automatic one waiting becomes what the rule asks for.
        Work.stopDescribing(context)
        request(Run.USER)
        request(Run.CHARGER)
        val user = running().single().id
        runBlocking { Work.replan(context, OnBattery.ANYTIME) }
        assertThat(running().single().id).isEqualTo(user)
        assertThat(waiting().map(::kind)).containsExactly(Run.BATTERY)
        assertThat(waiting().single().constraints.requiresCharging()).isFalse()

        // Out of "New photos only", the charger's run becomes an automatic request.
        Work.stopDescribing(context)
        Work.describeOnCharger(context)
        runBlocking { Work.replan(context, OnBattery.ANYTIME) }
        assertThat(chargerJobQueued()).isFalse()
        assertThat(live().map(::kind)).containsExactly(Run.BATTERY)
        Work.stopDescribing(context)
        Work.describeOnCharger(context)
        runBlocking { Work.replan(context, OnBattery.NEW_ONLY) }
        assertThat(chargerJobQueued()).isTrue()
    }

    @Test
    fun whatARunTakes() {
        for (rule in OnBattery.entries) {
            assertWithMessage("user, $rule").that(Work.scope(Run.USER, rule)).isEqualTo(Scope.ALL)
            assertWithMessage("charger, $rule").that(Work.scope(Run.CHARGER, rule)).isEqualTo(Scope.ALL)
        }
        assertThat(Work.scope(Run.BATTERY, OnBattery.ANYTIME)).isEqualTo(Scope.ALL)
        assertThat(Work.scope(Run.BATTERY, OnBattery.NEW_ONLY)).isEqualTo(Scope.NEW)
        assertThat(Work.scope(Run.BATTERY, OnBattery.CHARGER_ONLY)).isEqualTo(Scope.WAIT)
    }

    @Test
    fun aRunOnBatteryInNothingAsksForTheChargersInstead() {
        // Asked for before "Nothing" was chosen: it takes nothing, and a charger run waits.
        assertThat(runBlocking { Work.begin(context, Run.BATTERY, OnBattery.CHARGER_ONLY) }).isNull()
        assertThat(live().map(::kind)).containsExactly(Run.CHARGER)
        assertThat(runBlocking { Work.begin(context, Run.BATTERY, OnBattery.NEW_ONLY) }).isEqualTo(Scope.NEW)
        assertThat(runBlocking { Work.begin(context, Run.USER, OnBattery.CHARGER_ONLY) }).isEqualTo(Scope.ALL)
        assertThat(live().map(::kind)).containsExactly(Run.CHARGER)
    }

    private val now = 1_800_000_000_000L

    /** A photo, its key [key], taken [daysAgo] days before [now]. */
    private fun item(key: String, daysAgo: Double = 0.0) = MediaEntity(
        id = key.hashCode().toLong(), uri = "content://media/$key", isVideo = false, displayName = "$key.jpg",
        relativePath = "DCIM/Camera/", mimeType = "image/jpeg",
        sortDate = now - (daysAgo * TimeUnit.DAYS.toMillis(1)).toLong(), dateModified = 0, size = 0,
        width = 0, height = 0, orientation = 0, durationMs = 0, folder = 0, aiKey = key, generation = 0,
    )

    @Test
    fun aFileDescribedAgainGoesBesideTheRunGoing() {
        fun file(of: MediaEntity) = wm.getWorkInfosForUniqueWork(Work.fileWork(of.id)).get()
        // A run going: a library's first, say, which takes hours.
        request(Run.USER)
        val photo = item("photo")
        Work.describeFile(context, photo)
        // The file goes at once, not after the run, which goes on.
        assertThat(file(photo).single().state).isEqualTo(WorkInfo.State.RUNNING)
        assertThat(running().map(::kind)).containsExactly(Run.USER)
        // Asked for again while it goes: not doubled.
        Work.describeFile(context, photo)
        assertThat(file(photo)).hasSize(1)
        // Another file does not wait for it.
        val other = item("other")
        Work.describeFile(context, other)
        assertThat(file(other).single().state).isEqualTo(WorkInfo.State.RUNNING)
        // Stop stops them too.
        Work.stopDescribing(context)
        assertThat(wm.getWorkInfosByTag(Work.DESCRIBE_FILE).get().filter { !it.state.isFinished }).isEmpty()
    }

    @Test
    fun onBatteryARunTakesTheNewFilesUpToTheBudget() {
        val t = item("t", 0.1) // captioned already: waits for its text alone
        val a = item("a", 0.5)
        val b = item("b", 1.0)
        val c = item("c", 3.0)
        val edge = item("edge", 7.0)
        val old = item("old", 30.0)
        // Captions newest first; text by its own order.
        val captions = listOf(a, b, c, edge, old)
        val text = listOf(c, t, old, a)

        assertThat(Work.plan(Scope.ALL, captions, text, 0, now)).isEqualTo(Work.Plan(captions, text, leftForCharger = false))
        assertThat(Work.plan(Scope.NEW, captions, text, 100, now))
            .isEqualTo(Work.Plan(listOf(a, b, c, edge), listOf(c, t, a), leftForCharger = true))
        // The newest first, each file counted once whichever passes it waits for, each pass in its order.
        assertThat(Work.plan(Scope.NEW, captions, text, 3, now))
            .isEqualTo(Work.Plan(listOf(a, b), listOf(t, a), leftForCharger = true))
        assertThat(Work.plan(Scope.NEW, captions, text, 1, now))
            .isEqualTo(Work.Plan(emptyList(), listOf(t), leftForCharger = true))
        assertThat(Work.plan(Scope.NEW, captions, text, 0, now))
            .isEqualTo(Work.Plan(emptyList(), emptyList(), leftForCharger = true))
        // Nothing old, the budget enough: nothing left for the charger.
        assertThat(Work.plan(Scope.NEW, listOf(a, b), listOf(a), 2, now))
            .isEqualTo(Work.Plan(listOf(a, b), listOf(a), leftForCharger = false))
    }

    /** A store in memory: on Windows, DataStore's rename onto its file now and then fails while another program reads it. */
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test
    fun theBudgetIsADays() = runBlocking {
        val budget = BatteryBudget(MemoryStore())
        val day = LocalDate.of(2031, 3, 4)
        assertThat(budget.left(day)).isEqualTo(BatteryBudget.PER_DAY)
        repeat(3) { budget.use(day) }
        assertThat(budget.left(day)).isEqualTo(BatteryBudget.PER_DAY - 3)
        // A new day, a new budget.
        val next = day.plusDays(1)
        assertThat(budget.left(next)).isEqualTo(BatteryBudget.PER_DAY)
        repeat(BatteryBudget.PER_DAY + 2) { budget.use(next) }
        assertThat(budget.left(next)).isEqualTo(0)
    }

    @Test
    fun chargingIsReadFromTheBattery() {
        charging(true)
        assertThat(Work.charging(context)).isTrue()
        charging(false)
        assertThat(Work.charging(context)).isFalse()
    }

    @Test
    fun theSwitchItReplacedKeepsOnlyItsOn() {
        assertThat(onBatteryFrom(null, true)).isEqualTo(OnBattery.CHARGER_ONLY)
        // Off was stored whatever the user chose (every setting was written on any change): the default.
        assertThat(onBatteryFrom(null, false)).isNull()
        assertThat(onBatteryFrom(null, null)).isNull()
        assertThat(onBatteryFrom("ANYTIME", true)).isEqualTo(OnBattery.ANYTIME)
        // A name no longer known falls back to the switch, then the default.
        assertThat(onBatteryFrom("GONE", true)).isEqualTo(OnBattery.CHARGER_ONLY)
        assertThat(onBatteryFrom("GONE", false)).isNull()
    }
}
