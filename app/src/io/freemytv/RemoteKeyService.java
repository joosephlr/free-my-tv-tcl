package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * Also provides the Mouse Toggle mode via D-Pad arrows, toggled by double-clicking the remote's Voice button.
 */
public class RemoteKeyService extends AccessibilityService {
    private MouseToggle mouseToggle;

    private MouseToggle getMouseToggle() {
        if (mouseToggle == null) mouseToggle = new MouseToggle(this);
        return mouseToggle;
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        AppLog.w("RemoteKeyService key: " + event.getKeyCode() + " action: " + event.getAction());
        if (getMouseToggle().onKeyEvent(event)) return true;
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (mouseToggle != null) {
            mouseToggle.destroy();
            mouseToggle = null;
        }
        super.onDestroy();
    }
}
