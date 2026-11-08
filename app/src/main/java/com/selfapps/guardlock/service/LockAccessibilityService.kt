package com.selfapps.guardlock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.selfapps.guardlock.data.Prefs
import com.selfapps.guardlock.lock.LockEngine
import com.selfapps.guardlock.ui.LockActivity

/**
 * Watches which app comes to the foreground and covers it with [LockActivity] when blocked.
 *
 * Battery: nothing here polls. The service wakes only on window changes, when a new rule set
 * arrives ([LockEngine] listener), and once when a timed unlock runs out.
 *
 * It also blocks the Settings screens that add users or profiles. A new Android user would not
 * have GuardLock installed, so it would bypass every lock. This is best effort and depends on the
 * phone brand's screen text; full protection needs device-owner mode (see [Hardening]).
 */
class LockAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private var lastLaunch = 0L
    private var lastToast = 0L

    private val recheck = Runnable { currentPkg?.let { check(it) } }
    private val onRulesChanged: () -> Unit = { handler.post(recheck) }
    private val scanUsers = Runnable { scanForUserCreation() }

    override fun onServiceConnected() {
        LockEngine.addListener(onRulesChanged)
        handler.post(recheck)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (Prefs.pairId != null && (pkg == SETTINGS || pkg == SYSTEM_UI)) {
            // Screen text appears a moment after the window opens, so look twice.
            handler.removeCallbacks(scanUsers)
            handler.postDelayed(scanUsers, 350)
            handler.postDelayed(scanUsers, 1200)
        }

        // Notification shade / keyboard windows appear over the real app; don't treat them as
        // "the app the user is in", or we'd stop watching the app underneath.
        if (isTransient(pkg)) return
        currentPkg = pkg
        if (pkg != packageName) check(pkg)
    }

    private fun check(pkg: String) {
        if (pkg == packageName) return
        if (!LockEngine.isBlocked(pkg)) {
            // Open for a limited time? Look again exactly when the time runs out.
            LockEngine.msUntilBlocked(pkg)?.let {
                handler.removeCallbacks(recheck)
                handler.postDelayed(recheck, it + 50)
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return // debounce bursts of window events
        lastLaunch = now
        startActivity(
            Intent(this, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LockActivity.EXTRA_PKG, pkg)
        )
    }

    // ---------- block "add user / guest / profile" screens ----------

    private fun scanForUserCreation() {
        val root = rootInActiveWindow ?: return
        val hit = try {
            containsUserCreation(root, 0)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
        if (!hit) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = SystemClock.elapsedRealtime()
        if (now - lastToast > 3000) {
            lastToast = now
            Toast.makeText(this, "Adding users or profiles is blocked by GuardLock", Toast.LENGTH_LONG).show()
        }
    }

    private fun containsUserCreation(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 30) return false
        val text = node.text?.toString()?.trim()?.lowercase()
        if (text != null && text in USER_CREATION_TEXTS) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsUserCreation(child, depth + 1)
            @Suppress("DEPRECATION") child.recycle()
            if (found) return true
        }
        return false
    }

    private fun isTransient(pkg: String): Boolean {
        if (pkg == SYSTEM_UI) return true
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        LockEngine.removeListener(onRulesChanged)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
        const val SYSTEM_UI = "com.android.systemui"

        // Exact on-screen texts that only appear on the "add a user / guest / profile" screens.
        // ("Multiple users" is left out on purpose: it is also a normal row on the System page.)
        val USER_CREATION_TEXTS = setOf(
            "allow multiple users",
            "add user",
            "add guest",
            "add new user",
            "add user or profile",
            "add profile",
            "add a user",
            "new user",
            "create new user",
        )
    }
}
