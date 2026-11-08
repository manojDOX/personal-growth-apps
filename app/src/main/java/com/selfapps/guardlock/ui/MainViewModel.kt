package com.selfapps.guardlock.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selfapps.guardlock.GuardLockApp
import com.selfapps.guardlock.data.AppRule
import com.selfapps.guardlock.data.Health
import com.selfapps.guardlock.data.InstalledApp
import com.selfapps.guardlock.data.PairInfo
import com.selfapps.guardlock.data.PairRepository
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.data.UsageDay
import com.selfapps.guardlock.lock.LockEngine
import com.selfapps.guardlock.service.HeartbeatWorker
import com.selfapps.guardlock.service.SyncService
import com.selfapps.guardlock.usage.UsageSync
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Two-way: this phone is guarded by its partner AND guards its partner.
 * "my*" is data about THIS phone. "peer*" is data about the partner's phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    var pairId by mutableStateOf(Prefs.pairId)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var tab by mutableIntStateOf(0)

    val myUid = MutableStateFlow<String?>(null)
    val peerUid = MutableStateFlow<String?>(null)
    val pair = MutableStateFlow<PairInfo?>(null)

    val myApps = MutableStateFlow<List<AppRule>>(emptyList())
    val peerApps = MutableStateFlow<List<AppRule>>(emptyList())
    val peerHealth = MutableStateFlow<Health?>(null)
    val myUsage = MutableStateFlow<List<UsageDay>>(emptyList())
    val peerUsage = MutableStateFlow<List<UsageDay>>(emptyList())

    private var observer: Job? = null

    init {
        observe()
        if (pairId != null) startProtection()
    }

    private fun observe() {
        observer?.cancel()
        val id = pairId ?: return
        observer = viewModelScope.launch {
            val me = try {
                PairRepository.uid()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                return@launch
            }
            myUid.value = me

            launch {
                PairRepository.pairFlow(id).catch { error = it.message }.collect {
                    pair.value = it
                    peerUid.value = it?.peerOf(me)
                }
            }
            launch { PairRepository.appsFlow(id, me).catch { error = it.message }.collect { myApps.value = it } }
            launch { PairRepository.usageFlow(id, me).catch { error = it.message }.collect { myUsage.value = it } }

            // Everything about the partner follows the partner's uid, which appears once they join.
            launch {
                peerUid.flatMapLatest { u ->
                    if (u == null) flowOf(emptyList<AppRule>())
                    else PairRepository.appsFlow(id, u).catch { e -> error = e.message; emit(emptyList()) }
                }.collect { peerApps.value = it }
            }
            launch {
                peerUid.flatMapLatest { u ->
                    if (u == null) flowOf<Health?>(null)
                    else PairRepository.healthFlow(id, u).catch { e -> error = e.message; emit(null) }
                }.collect { peerHealth.value = it }
            }
            launch {
                peerUid.flatMapLatest { u ->
                    if (u == null) flowOf(emptyList<UsageDay>())
                    else PairRepository.usageFlow(id, u).catch { e -> error = e.message; emit(emptyList()) }
                }.collect { peerUsage.value = it }
            }
        }
    }

    private fun startProtection() {
        val ctx = getApplication<Application>()
        SyncService.start(ctx)
        HeartbeatWorker.schedule(ctx)
    }

    private fun launchOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    // ---------- onboarding ----------

    fun createPair() = launchOp {
        val code = PairRepository.createPair()
        Prefs.pairId = code
        pairId = code
        observe()
        startProtection()
    }

    fun join(code: String) = launchOp {
        val id = PairRepository.joinPair(code)
        Prefs.pairId = id
        pairId = id
        observe()
        startProtection()
    }

    /**
     * Dev helper. Refused while any app is locked on THIS phone, so it cannot be used to slip out
     * of a lock. (The cached lock list is also wiped, so this is only for a clean restart.)
     */
    fun unpair() {
        if (myApps.value.any { it.locked }) {
            error = "Your partner has apps locked on this phone. Ask them to unlock or remove them first."
            return
        }
        val ctx = getApplication<Application>()
        observer?.cancel()
        SyncService.stop(ctx)
        HeartbeatWorker.cancel(ctx)
        Prefs.clear()
        Prefs.schema = GuardLockApp.SCHEMA
        LockEngine.update(emptyList(), persist = false)
        pairId = null
        myUid.value = null
        peerUid.value = null
        pair.value = null
        myApps.value = emptyList()
        peerApps.value = emptyList()
        peerHealth.value = null
        myUsage.value = emptyList()
        peerUsage.value = emptyList()
        tab = 0
        error = null
    }

    // ---------- my phone: I may only ADD locks ----------

    fun addMyApps(items: List<InstalledApp>) = launchOp {
        val id = pairId ?: return@launchOp
        val me = myUid.value ?: return@launchOp
        items.forEach { PairRepository.addOwnApp(id, me, it.pkg, it.name) }
    }

    /** Upload fresh screen time so the partner's charts are current when I open the app. */
    fun refreshMyUsage() {
        viewModelScope.launch {
            runCatching { UsageSync.push(getApplication()) }
        }
    }

    // ---------- partner's phone: I may lock, unlock, time-limit and remove ----------

    fun setLocked(pkg: String, locked: Boolean) = launchOp {
        PairRepository.setLocked(pairId ?: return@launchOp, peerUid.value ?: return@launchOp, pkg, locked)
    }

    fun unlockForMinutes(pkg: String, minutes: Int) = launchOp {
        PairRepository.unlockUntil(
            pairId ?: return@launchOp,
            peerUid.value ?: return@launchOp,
            pkg,
            System.currentTimeMillis() + minutes * 60_000L,
        )
    }

    fun unlockUntilMidnight(pkg: String) = launchOp {
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        PairRepository.unlockUntil(pairId ?: return@launchOp, peerUid.value ?: return@launchOp, pkg, midnight)
    }

    fun remove(pkg: String) = launchOp {
        PairRepository.removeApp(pairId ?: return@launchOp, peerUid.value ?: return@launchOp, pkg)
    }
}
