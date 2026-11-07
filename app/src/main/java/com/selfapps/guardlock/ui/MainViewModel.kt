package com.selfapps.guardlock.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.selfapps.guardlock.data.AppRule
import com.selfapps.guardlock.data.InstalledApp
import com.selfapps.guardlock.data.PairInfo
import com.selfapps.guardlock.data.PairRepository
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.data.Role
import com.selfapps.guardlock.service.HeartbeatWorker
import com.selfapps.guardlock.service.SyncService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var role by mutableStateOf(Prefs.role)
        private set
    var pairId by mutableStateOf(Prefs.pairId)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    val pair = MutableStateFlow<PairInfo?>(null)
    val apps = MutableStateFlow<List<AppRule>>(emptyList())

    private var observer: Job? = null

    init {
        observe()
        if (role == Role.GUARDED && pairId != null) startProtection()
        if (role == Role.GUARDIAN && pairId == null) createPair()
    }

    private fun observe() {
        observer?.cancel()
        val id = pairId ?: return
        observer = viewModelScope.launch {
            launch { PairRepository.pairFlow(id).catch { error = it.message }.collect { pair.value = it } }
            launch { PairRepository.appsFlow(id).catch { error = it.message }.collect { apps.value = it } }
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

    fun chooseRole(r: Role) {
        Prefs.role = r
        role = r
        if (r == Role.GUARDIAN) createPair()
    }

    fun createPair() = launchOp {
        val code = PairRepository.createPair()
        Prefs.pairId = code
        pairId = code
        observe()
    }

    fun join(code: String) = launchOp {
        val id = PairRepository.joinPair(code)
        Prefs.pairId = id
        pairId = id
        observe()
        startProtection()
    }

    /** Dev helper, guardian side only. The guarded side deliberately has no way out. */
    fun unpair() {
        observer?.cancel()
        Prefs.clear()
        role = null
        pairId = null
        pair.value = null
        apps.value = emptyList()
    }

    // ---------- app rules ----------

    fun addApps(items: List<InstalledApp>) = launchOp {
        val id = pairId ?: return@launchOp
        val by = if (role == Role.GUARDED) "guarded" else "guardian"
        items.forEach { PairRepository.addApp(id, it.pkg, it.name, by) }
    }

    fun setLocked(pkg: String, locked: Boolean) = launchOp {
        PairRepository.setLocked(pairId ?: return@launchOp, pkg, locked)
    }

    fun unlockForMinutes(pkg: String, minutes: Int) = launchOp {
        PairRepository.unlockUntil(
            pairId ?: return@launchOp, pkg, System.currentTimeMillis() + minutes * 60_000L
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
        PairRepository.unlockUntil(pairId ?: return@launchOp, pkg, midnight)
    }

    fun remove(pkg: String) = launchOp {
        PairRepository.removeApp(pairId ?: return@launchOp, pkg)
    }
}
