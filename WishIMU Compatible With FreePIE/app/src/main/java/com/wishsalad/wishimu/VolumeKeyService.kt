package com.wishsalad.wishimu

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Intercepts volume key events system-wide via Android's Accessibility framework.
 *
 * Why this is needed
 * ──────────────────
 * Physical volume buttons follow three distinct paths depending on context:
 *
 *  1. Screen OFF / locked  →  Activity is shown over the lock screen (setShowWhenLocked)
 *     and is the foreground window  →  MainActivity.onKeyDown / onKeyUp fire directly.
 *     True press + release, instant.  ✓ already worked.
 *
 *  2. Screen ON, WishIMU in foreground  →  same as case 1: onKeyDown/onKeyUp fire.  ✓
 *
 *  3. Screen ON, WishIMU in BACKGROUND (game / other app in foreground)
 *     →  onKeyDown never fires.  VolumeProvider.onAdjustVolume fires instead,
 *     but it only receives a direction (+1/-1), never a KEY_UP event.
 *     A timer must simulate the release: rapid taps within the timer window
 *     get merged into a single long press, so rapid fire is broken.  ✗
 *
 * This service fixes case 3 by intercepting key events at the AccessibilityService
 * layer, which runs before the focused window — i.e. before the game's onKeyDown
 * and before VolumeProvider — and provides true ACTION_DOWN + ACTION_UP pairs.
 * The result is identical press/release semantics to cases 1 and 2, regardless
 * of which app is in the foreground.
 *
 * Requires the user to enable WishIMU once in Settings → Accessibility.
 * MainActivity shows a prompt with a direct link to that settings screen.
 */
class VolumeKeyService : AccessibilityService() {

    companion object {
        /**
         * True while this service is connected and running.
         * Read by VolumeProvider to skip its timer-based simulation when the
         * AccessibilityService already provides accurate press/release events.
         */
        @JvmField @Volatile var isEnabled = false
    }

    override fun onServiceConnected() {
        // FLAG_REQUEST_FILTER_KEY_EVENTS is declared statically in
        // res/xml/accessibility_service_config.xml (canRequestFilterKeyEvents="true").
        // Setting it programmatically here is redundant and triggers the lint warning
        // AccessibilityServiceFlag — so we only set the flag in XML.
        isEnabled = true
    }

    /**
     * Called for every hardware key event before it reaches the focused window.
     * Returns true to consume the event (preventing system volume change);
     * returns false to pass it through when WishIMU's volume-button feature is off.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!UdpSenderService.started || !UdpSenderService.volumeButtonsEnabled) return false

        // When the Activity is in the foreground (including shown over the lock screen via
        // setShowWhenLocked), onKeyDown/onKeyUp in MainActivity already handle the event
        // directly — no interception needed.  The AccessibilityService is only needed for
        // the background case (screen unlocked, another app in the foreground).
        if (MainActivity.isInForeground) return false

        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP   -> 0x01
            KeyEvent.KEYCODE_VOLUME_DOWN -> 0x02
            else                         -> return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                UdpSenderService.buttonState.updateAndGet { it or bit }
                UdpSenderService.wakeWorker()   // send press packet immediately
            }
            KeyEvent.ACTION_UP -> {
                UdpSenderService.buttonState.updateAndGet { it and bit.inv() }
                UdpSenderService.wakeWorker()   // send release packet immediately
            }
        }
        return true  // consume — do not change system volume
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isEnabled = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isEnabled = false
        super.onDestroy()
    }
}
