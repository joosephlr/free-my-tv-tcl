package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 * Also provides the Mouse Toggle mode via D-Pad arrows, toggled by double-clicking the remote's Voice button.
 */
public class RemoteKeyService extends AccessibilityService {
    private MouseToggle mouseToggle;

    private final BroadcastReceiver toggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            AppLog.w("RemoteKeyService broadcast received: " + action);
            if ("io.freemytv.action.TOGGLE_MOUSE".equals(action)) {
                getMouseToggle().toggle(true);
            } else if ("io.freemytv.action.TEST_CLICK".equals(action)) {
                getMouseToggle().testClick();
            }
        }
    };

    private MouseToggle getMouseToggle() {
        if (mouseToggle == null) mouseToggle = new MouseToggle(this);
        return mouseToggle;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.w("RemoteKeyService onCreate");
        IntentFilter filter = new IntentFilter();
        filter.addAction("io.freemytv.action.TOGGLE_MOUSE");
        filter.addAction("io.freemytv.action.TEST_CLICK");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(toggleReceiver, filter);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        AppLog.w("RemoteKeyService onServiceConnected: flags=" + info.flags);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        AppLog.w("RemoteKeyService key: " + event.getKeyCode() + " action: " + event.getAction());
        if (getMouseToggle().onKeyEvent(event)) return true;
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        try {
            unregisterReceiver(toggleReceiver);
        } catch (Exception ignored) {}
        if (mouseToggle != null) {
            mouseToggle.destroy();
            mouseToggle = null;
        }
        super.onDestroy();
    }
}
