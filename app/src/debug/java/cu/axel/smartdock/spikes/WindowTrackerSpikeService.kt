package cu.axel.smartdock.spikes

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Phase 0 spike (e): can we follow freeform window bounds smoothly enough to draw our own title bars?
 * Debug builds only. It logs ONLY window ids, types and bounds, never titles, text or node content
 * (CLAUDE.md, security rule 5). Enable it just for the test and disable it afterwards.
 */
class WindowTrackerSpikeService : AccessibilityService() {
    private var lastEvent = 0L
    private var events = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "spike e) servicio de ventanas conectado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (event.windowChanges and AccessibilityEvent.WINDOWS_CHANGE_BOUNDS == 0) return
        val now = SystemClock.uptimeMillis()
        val delta = if (lastEvent == 0L) -1 else now - lastEvent
        lastEvent = now
        events++
        val window = windows.firstOrNull { it.id == event.windowId }
        val bounds = Rect()
        window?.getBoundsInScreen(bounds)
        Log.d(TAG, "spike e) #$events win=${event.windowId} type=${window?.type} bounds=$bounds dt=${delta}ms")
    }

    override fun onInterrupt() {}

    private companion object {
        const val TAG = "EG"
    }
}
