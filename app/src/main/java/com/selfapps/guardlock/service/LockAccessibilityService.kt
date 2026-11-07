package com.selfapps.guardlock.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.selfapps.guardlock.lock.LockEngine
import com.selfapps.guardlock.ui.LockActivity

/**
 * Watches which app comes to the foreground and covers it with [LockActivity] when blocked.
 * A light 2s tick also re-checks the current app, so a guardian pressing "Lock" (or a timed
 * unlock expiring) takes effect while the user is already inside the app.
 */
class LockAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private var lastLaunch = 0L

    private val tick = object : Runnable {
        override fun run() {
            currentPkg?.let { check(it) }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onServiceConnected() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Notification shade / keyboard windows appear over the real app; don't treat them as
        // "the app the user is in", or we'd stop watching the app underneath.
        if (isTransient(pkg)) return
        currentPkg = pkg
        if (pkg != packageName) check(pkg)
    }

    private fun check(pkg: String) {
        if (pkg == packageName || !LockEngine.isBlocked(pkg)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 800) return // debounce bursts of window events
        lastLaunch = now
        startActivity(
            Intent(this, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LockActivity.EXTRA_PKG, pkg)
        )
    }

    private fun isTransient(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return true
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
