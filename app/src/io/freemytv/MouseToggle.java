package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import java.util.List;

/**
 * Simulates a virtual mouse cursor driven by the remote's D-Pad arrows, toggled by double-pressing
 * the remote's Voice Command / Assistant button (or double-pressing Back while active).
 */
final class MouseToggle {
    private static final long DOUBLE_CLICK_WINDOW_MS = 650;
    private static final long INACTIVITY_TIMEOUT_MS = 6_000;
    private static final long EDGE_HOLD_DELAY_MS = 140;
    private static final long SCROLL_INTERVAL_MS = 280;
    private static final float TOP_EDGE_THRESHOLD = 25f;
    private static final float BOTTOM_EDGE_MARGIN = 35f;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager.LayoutParams params;
    private final CursorView cursorView;

    private boolean active = false;
    private boolean viewAttached = false;
    private float cursorX = 960, cursorY = 540;
    private int screenWidth = 1920, screenHeight = 1080;

    private long lastVoicePressTime = 0;
    private long lastBackPressTime = 0;

    private boolean dpadUp = false;
    private boolean dpadDown = false;
    private boolean dpadLeft = false;
    private boolean dpadRight = false;
    private long holdStartTime = 0;
    private long edgeReachTime = 0;
    private long lastScrollTime = 0;
    private long okPressDownTime = 0;

    private final Runnable singleVoicePressFallback;
    private final Runnable inactivityTimeout = () -> {
        if (active) toggle(false);
    };

    private final Runnable moveLoop = new Runnable() {
        @Override
        public void run() {
            if (!active || (!dpadUp && !dpadDown && !dpadLeft && !dpadRight)) return;

            long now = SystemClock.uptimeMillis();
            long elapsed = now - holdStartTime;

            // Speed calculation:
            // Starts at 5px per tick (~300px/s) for the first 120ms (fine precision)
            // Accelerates smoothly up to 32px per tick (~1920px/s) over ~1.1 seconds
            float speed;
            if (elapsed < 120) {
                speed = 5.0f;
            } else {
                float progress = Math.min(1.0f, (elapsed - 120) / 1100f);
                speed = 5.0f + progress * progress * 27.0f;
            }

            float dx = 0, dy = 0;
            if (dpadUp) dy -= speed;
            if (dpadDown) dy += speed;
            if (dpadLeft) dx -= speed;
            if (dpadRight) dx += speed;

            cursorX = Math.max(0, Math.min(screenWidth - 10, cursorX + dx));
            cursorY = Math.max(0, Math.min(screenHeight - 10, cursorY + dy));

            // Edge scroll: only trigger scrolling when cursor reaches the edge of the screen
            final float bottomEdge = screenHeight - BOTTOM_EDGE_MARGIN;
            final float topEdge = TOP_EDGE_THRESHOLD;

            if (dpadDown && cursorY >= bottomEdge) {
                if (edgeReachTime == 0) edgeReachTime = now;
                if (now - edgeReachTime >= EDGE_HOLD_DELAY_MS) {
                    if (now - lastScrollTime >= SCROLL_INTERVAL_MS) {
                        lastScrollTime = now;
                        performScroll(true);
                    }
                }
            } else if (dpadUp && cursorY <= topEdge) {
                if (edgeReachTime == 0) edgeReachTime = now;
                if (now - edgeReachTime >= EDGE_HOLD_DELAY_MS) {
                    if (now - lastScrollTime >= SCROLL_INTERVAL_MS) {
                        lastScrollTime = now;
                        performScroll(false);
                    }
                }
            } else {
                edgeReachTime = 0;
                lastScrollTime = 0;
            }

            if (viewAttached) {
                params.x = (int) cursorX;
                params.y = (int) cursorY;
                try {
                    windowManager.updateViewLayout(cursorView, params);
                } catch (Exception ignored) {}
            }

            handler.removeCallbacks(inactivityTimeout);
            handler.postDelayed(inactivityTimeout, INACTIVITY_TIMEOUT_MS);

            handler.postDelayed(this, 16);
        }
    };

    MouseToggle(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.singleVoicePressFallback = () -> {
            if (!active) {
                try {
                    Intent intent = new Intent(Intent.ACTION_ASSIST);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    service.startActivity(intent);
                } catch (Exception ignored) {}
            }
        };
        updateScreenDimensions();

        cursorX = screenWidth / 2f;
        cursorY = screenHeight / 2f;

        cursorView = new CursorView(service);

        params = new WindowManager.LayoutParams(
                80,
                96,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = (int) cursorX;
        params.y = (int) cursorY;
    }

    private void updateScreenDimensions() {
        DisplayMetrics dm = service.getResources().getDisplayMetrics();
        if (dm.widthPixels > 0 && dm.heightPixels > 0) {
            screenWidth = dm.widthPixels;
            screenHeight = dm.heightPixels;
        }
    }

    private static final int KEY_TCL_123 = 4056;

    private static boolean isTriggerKey(int keyCode) {
        return keyCode == KEY_TCL_123
                || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == KeyEvent.KEYCODE_ASSIST
                || keyCode == KeyEvent.KEYCODE_SEARCH
                || keyCode == KeyEvent.KEYCODE_MUTE;
    }

    boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean down = action == KeyEvent.ACTION_DOWN;

        // Check for double-click on 123 or Voice button
        if (isTriggerKey(keyCode)) {
            if (down) {
                if (event.getRepeatCount() == 0) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastVoicePressTime <= DOUBLE_CLICK_WINDOW_MS) {
                        AppLog.w("MouseToggle: double click detected on key " + keyCode);
                        handler.removeCallbacks(singleVoicePressFallback);
                        lastVoicePressTime = 0;
                        toggle(true);
                        return true;
                    } else {
                        AppLog.w("MouseToggle: first click on key " + keyCode);
                        lastVoicePressTime = now;
                        if (keyCode != KEY_TCL_123) {
                            handler.removeCallbacks(singleVoicePressFallback);
                            handler.postDelayed(singleVoicePressFallback, DOUBLE_CLICK_WINDOW_MS);
                        }
                        return true;
                    }
                }
                return true;
            } else {
                return true;
            }
        }

        if (!active) return false;

        // Reset inactivity timer on any user key while mouse mode is active
        handler.removeCallbacks(inactivityTimeout);
        handler.postDelayed(inactivityTimeout, INACTIVITY_TIMEOUT_MS);

        // Fail-safe: double-clicking Back while in mouse mode also turns it off
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (down && event.getRepeatCount() == 0) {
                long now = SystemClock.uptimeMillis();
                if (now - lastBackPressTime <= DOUBLE_CLICK_WINDOW_MS) {
                    lastBackPressTime = 0;
                    toggle(true);
                    return true;
                }
                lastBackPressTime = now;
            }
            return false; // single back passes through to app
        }

        // D-Pad movement
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                boolean wasMoving = dpadUp || dpadDown || dpadLeft || dpadRight;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dpadUp = down;
                else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dpadDown = down;
                else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dpadLeft = down;
                else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dpadRight = down;

                boolean isMoving = dpadUp || dpadDown || dpadLeft || dpadRight;
                if (isMoving) {
                    if (!wasMoving) {
                        holdStartTime = SystemClock.uptimeMillis();
                        edgeReachTime = 0;
                        lastScrollTime = 0;
                        handler.removeCallbacks(moveLoop);
                        moveLoop.run();
                    }
                } else {
                    edgeReachTime = 0;
                    lastScrollTime = 0;
                    handler.removeCallbacks(moveLoop);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) {
                        okPressDownTime = SystemClock.uptimeMillis();
                        cursorView.setPressed(true);
                    }
                } else {
                    cursorView.setPressed(false);
                    long pressDuration = SystemClock.uptimeMillis() - okPressDownTime;
                    clickAt(cursorX + 6, cursorY + 6, pressDuration >= 500);
                }
                return true;

            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down && event.getRepeatCount() == 0) performScroll(false);
                return true;

            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down && event.getRepeatCount() == 0) performScroll(true);
                return true;

            default:
                return false;
        }
    }

    void testClick() {
        clickAt(cursorX + 6, cursorY + 6, false);
    }

    void toggle(boolean showToast) {
        active = !active;
        AppLog.w("MouseToggle active: " + active);
        dpadUp = false;
        dpadDown = false;
        dpadLeft = false;
        dpadRight = false;
        handler.removeCallbacks(moveLoop);
        handler.removeCallbacks(inactivityTimeout);
        handler.post(() -> {
            if (active) {
                updateScreenDimensions();
                params.x = (int) cursorX;
                params.y = (int) cursorY;
                if (!viewAttached) {
                    try {
                        windowManager.addView(cursorView, params);
                        viewAttached = true;
                        AppLog.w("cursorView attached at (" + params.x + ", " + params.y + ")");
                    } catch (Exception e) {
                        AppLog.w("addView failed: " + e.getMessage());
                    }
                }
                handler.postDelayed(inactivityTimeout, INACTIVITY_TIMEOUT_MS);
                if (showToast) {
                    try {
                        Toast.makeText(service.getApplicationContext(), "Modo Mouse ativado", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
            } else {
                if (viewAttached) {
                    try {
                        windowManager.removeView(cursorView);
                        viewAttached = false;
                        AppLog.w("cursorView detached");
                    } catch (Exception e) {
                        AppLog.w("removeView failed: " + e.getMessage());
                    }
                }
                if (showToast) {
                    try {
                        Toast.makeText(service.getApplicationContext(), "Modo Mouse desativado", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    private void clickAt(float x, float y, boolean longPress) {
        AppLog.w("clickAt (" + x + ", " + y + ") longPress=" + longPress);
        // Try clicking the UI accessibility node at the cursor position
        boolean nodeClicked = clickNodeAt(x, y, longPress);
        AppLog.w("clickAt: nodeClicked=" + nodeClicked);

        // Also dispatch standard touch gesture stroke
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        clickPath.lineTo(x, y + 1f); // Essential: non-zero length stroke prevents Android onCancelled
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.setDisplayId(Display.DEFAULT_DISPLAY);
        long duration = longPress ? 600 : 80;
        builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, duration));
        service.dispatchGesture(builder.build(), new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                AppLog.w("clickAt completed");
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                AppLog.w("clickAt cancelled");
            }
        }, handler);
    }

    private AccessibilityNodeInfo getActiveRootNode() {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) return root;
        } catch (Exception ignored) {}

        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w.isFocused() || w.isActive()) {
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root != null) return root;
                    }
                }
                for (AccessibilityWindowInfo w : windows) {
                    if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        AccessibilityNodeInfo root = w.getRoot();
                        if (root != null) return root;
                    }
                }
                for (AccessibilityWindowInfo w : windows) {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null) return root;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean clickNodeAt(float x, float y, boolean longPress) {
        try {
            AccessibilityNodeInfo root = getActiveRootNode();
            if (root == null) {
                AppLog.w("clickNodeAt: root is null");
                return false;
            }
            AccessibilityNodeInfo target = findClickableNodeAt(root, (int) x, (int) y);
            if (target != null) {
                int action = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
                boolean success = target.performAction(action);
                if (!success) {
                    AccessibilityNodeInfo parent = target.getParent();
                    if (parent != null) {
                        success = parent.performAction(action);
                    }
                }
                AppLog.w("clickNodeAt: performed " + (longPress ? "LONG_CLICK" : "CLICK")
                        + " on " + target.getClassName() + " -> " + success);
                if (!success && !longPress) {
                    target.performAction(AccessibilityNodeInfo.ACTION_SELECT);
                    target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                }
                return success;
            } else {
                AppLog.w("clickNodeAt: no clickable node found at (" + x + ", " + y + ")");
            }
        } catch (Exception e) {
            AppLog.w("clickNodeAt error: " + e.getMessage());
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableNodeAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) {
            return null;
        }
        // First check children to find the deepest/most specific clickable element under the pointer
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo hit = findClickableNodeAt(child, x, y);
                if (hit != null) return hit;
            }
        }
        if (node.isClickable() || node.isCheckable() || node.isFocusable()) {
            return node;
        }
        return null;
    }

    boolean performScroll(boolean down) {
        AppLog.w("performScroll: down=" + down + " at (" + cursorX + ", " + cursorY + ")");
        AccessibilityNodeInfo root = getActiveRootNode();
        if (root == null) {
            scrollGesture(down);
            return false;
        }

        // 1. Check under cursor
        AccessibilityNodeInfo leaf = findNodeAt(root, (int) (cursorX + 6), (int) (cursorY + 6));
        AccessibilityNodeInfo target1 = getBestScrollTarget(findScrollableAncestor(leaf, true), true);
        if (target1 != null) {
            boolean ok = performScrollAction(target1, down);
            AppLog.w("performScroll: on target1 " + target1.getClassName() + " (" + target1.getViewIdResourceName() + ") -> " + ok);
            if (ok) return true;
        }

        // 2. Check middle of content area along cursor's column (avoids top/bottom nav bars)
        float midY = screenHeight * 0.5f;
        AccessibilityNodeInfo midLeaf = findNodeAt(root, (int) (cursorX + 6), (int) midY);
        AccessibilityNodeInfo target2 = getBestScrollTarget(findScrollableAncestor(midLeaf, true), true);
        if (target2 != null && target2 != target1) {
            boolean ok = performScrollAction(target2, down);
            AppLog.w("performScroll: on target2 " + target2.getClassName() + " (" + target2.getViewIdResourceName() + ") -> " + ok);
            if (ok) return true;
        }

        // 3. Check screen center (screenWidth * 0.5f, screenHeight * 0.5f)
        float midX = screenWidth * 0.5f;
        AccessibilityNodeInfo centerLeaf = findNodeAt(root, (int) midX, (int) midY);
        AccessibilityNodeInfo target3 = getBestScrollTarget(findScrollableAncestor(centerLeaf, true), true);
        if (target3 != null && target3 != target1 && target3 != target2) {
            boolean ok = performScrollAction(target3, down);
            AppLog.w("performScroll: on target3 " + target3.getClassName() + " (" + target3.getViewIdResourceName() + ") -> " + ok);
            if (ok) return true;
        }

        // 4. Find deepest scrollable container in the entire window (prioritizes inner RecyclerView over outer CoordinatorLayout)
        AccessibilityNodeInfo target4 = findDeepestScrollableNode(root, true);
        if (target4 != null && target4 != target1 && target4 != target2 && target4 != target3) {
            boolean ok = performScrollAction(target4, down);
            AppLog.w("performScroll: on target4 " + target4.getClassName() + " (" + target4.getViewIdResourceName() + ") -> " + ok);
            if (ok) return true;
        }

        // 5. Fallback: gesture scroll
        scrollGesture(down);
        return false;
    }

    private AccessibilityNodeInfo getBestScrollTarget(AccessibilityNodeInfo candidate, boolean vertical) {
        if (candidate == null) return null;
        AccessibilityNodeInfo deep = findDeepestScrollableNode(candidate, vertical);
        return deep != null ? deep : candidate;
    }

    private AccessibilityNodeInfo findNodeAt(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.contains(x, y)) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo hit = findNodeAt(child, x, y);
                if (hit != null) return hit;
            }
        }
        return node;
    }

    private AccessibilityNodeInfo findScrollableAncestor(AccessibilityNodeInfo node, boolean vertical) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 20) {
            if (isNodeScrollable(current, vertical)) {
                return current;
            }
            current = current.getParent();
            depth++;
        }
        return null;
    }

    private AccessibilityNodeInfo findDeepestScrollableNode(AccessibilityNodeInfo node, boolean vertical) {
        if (node == null) return null;

        CharSequence className = node.getClassName();
        String name = className != null ? className.toString() : "";

        // If this node is already a scrollable list container, it is the leaf list - don't scan its 100s of child items!
        if (name.contains("RecyclerView") || name.contains("VerticalGridView") || name.contains("ListView")) {
            if (isNodeScrollable(node, vertical)) {
                return node;
            }
        }

        // Search children so inner RecyclerView is preferred over outer CoordinatorLayout/ScrollView
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo deepHit = findDeepestScrollableNode(child, vertical);
                if (deepHit != null) return deepHit;
            }
        }

        // If no child is scrollable, check if this node itself is scrollable
        if (isNodeScrollable(node, vertical)) {
            return node;
        }

        return null;
    }

    private boolean isNodeScrollable(AccessibilityNodeInfo node, boolean vertical) {
        if (node == null) return false;

        CharSequence className = node.getClassName();
        String name = className != null ? className.toString() : "";

        if (vertical && (name.contains("HorizontalScrollView") || name.contains("HorizontalGridView"))) {
            return false;
        }
        if (!vertical && name.contains("VerticalGridView")) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                int id = action.getId();
                if (vertical) {
                    if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
                            || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) {
                        return true;
                    }
                } else {
                    if (id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
                            || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()) {
                        return true;
                    }
                }
            }
        }

        if (node.isScrollable()) {
            if (vertical) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.height() > 0 && bounds.width() > 0) {
                    if (bounds.height() < 250 && bounds.width() > bounds.height() * 3) {
                        return false;
                    }
                }
            }
            return true;
        }

        if (vertical) {
            if (name.contains("VerticalGridView")
                    || name.contains("ScrollView")
                    || name.contains("ListView")
                    || name.contains("RecyclerView")
                    || name.contains("WebView")) {
                return true;
            }
        } else {
            if (name.contains("HorizontalScrollView")
                    || name.contains("HorizontalGridView")) {
                return true;
            }
        }

        int actions = node.getActions();
        if ((actions & AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != 0
                || (actions & AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != 0) {
            return true;
        }

        return false;
    }

    private boolean performScrollAction(AccessibilityNodeInfo node, boolean down) {
        if (node == null) return false;
        try {
            int targetAction = down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            int specificAction = down
                    ? (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId() : 0)
                    : (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId() : 0);

            boolean hasSpecific = false;
            boolean hasForwardBackward = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (AccessibilityNodeInfo.AccessibilityAction a : node.getActionList()) {
                    if (a.getId() == specificAction) hasSpecific = true;
                    if (a.getId() == targetAction) hasForwardBackward = true;
                }
            }

            int actionsMask = node.getActions();
            if ((actionsMask & targetAction) != 0) {
                hasForwardBackward = true;
            }

            if (hasSpecific) {
                boolean ok = node.performAction(specificAction);
                if (ok) return true;
            }

            if (hasForwardBackward) {
                boolean ok = node.performAction(targetAction);
                if (ok) return true;
            }

            if (node.performAction(targetAction)) return true;
            if (specificAction != 0 && node.performAction(specificAction)) return true;
        } catch (Exception e) {
            AppLog.w("performScrollAction error: " + e.getMessage());
        }
        return false;
    }

    private void scrollGesture(boolean down) {
        try {
            Path scrollPath = new Path();
            float startY = down ? screenHeight * 0.72f : screenHeight * 0.28f;
            float endY = down ? screenHeight * 0.28f : screenHeight * 0.72f;
            float midX = screenWidth * 0.5f;
            scrollPath.moveTo(midX, startY);
            scrollPath.lineTo(midX, endY);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.setDisplayId(Display.DEFAULT_DISPLAY);
            builder.addStroke(new GestureDescription.StrokeDescription(scrollPath, 0, 180));
            service.dispatchGesture(builder.build(), null, handler);
        } catch (Exception ignored) {}
    }

    void destroy() {
        dpadUp = false;
        dpadDown = false;
        dpadLeft = false;
        dpadRight = false;
        edgeReachTime = 0;
        lastScrollTime = 0;
        handler.removeCallbacksAndMessages(null);
        if (viewAttached) {
            try {
                windowManager.removeView(cursorView);
                viewAttached = false;
            } catch (Exception ignored) {}
        }
    }

    private static final class CursorView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path shadowPath = new Path();
        private boolean pressed = false;

        CursorView(Context context) {
            super(context);
            fillPaint.setColor(Color.WHITE);
            fillPaint.setStyle(Paint.Style.FILL);

            strokePaint.setColor(0xFF1A1A1A);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(3.5f);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);

            shadowPaint.setColor(0x55000000);
            shadowPaint.setStyle(Paint.Style.FILL);

            // Pointer arrow geometry scaled for comfortable TV viewing from the couch:
            path.moveTo(6, 6);
            path.lineTo(6, 62);
            path.lineTo(22, 50);
            path.lineTo(34, 74);
            path.lineTo(43, 69);
            path.lineTo(31, 45);
            path.lineTo(49, 45);
            path.close();

            shadowPath.set(path);
            shadowPath.offset(3, 4);
        }

        @Override public void setPressed(boolean p) {
            super.setPressed(p);
            if (pressed != p) {
                pressed = p;
                fillPaint.setColor(p ? 0xFF30D158 : Color.WHITE);
                invalidate();
            }
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(80, 96);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawPath(shadowPath, shadowPaint);
            canvas.drawPath(path, fillPaint);
            canvas.drawPath(path, strokePaint);
        }
    }
}
