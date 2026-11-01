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
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Simulates a virtual mouse cursor driven by the remote's D-Pad arrows, toggled by double-pressing
 * the remote's Voice Command / Assistant button (or double-pressing Back while active).
 */
final class MouseToggle {
    private static final long DOUBLE_CLICK_WINDOW_MS = 650;
    private static final long INACTIVITY_TIMEOUT_MS = 60_000;

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

    private final Runnable singleVoicePressFallback;
    private final Runnable inactivityTimeout = () -> {
        if (active) toggle(false);
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
                if (down) {
                    float speed = Math.min(55f, 18f + event.getRepeatCount() * 4f);
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP) cursorY = Math.max(0, cursorY - speed);
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) cursorY = Math.min(screenHeight, cursorY + speed);
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) cursorX = Math.max(0, cursorX - speed);
                    else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) cursorX = Math.min(screenWidth, cursorX + speed);

                    params.x = (int) cursorX;
                    params.y = (int) cursorY;
                    if (viewAttached) {
                        try {
                            windowManager.updateViewLayout(cursorView, params);
                        } catch (Exception ignored) {}
                    }
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) cursorView.setPressed(true);
                } else {
                    cursorView.setPressed(false);
                    clickAt(cursorX, cursorY);
                }
                return true;

            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down && event.getRepeatCount() == 0) scroll(false);
                return true;

            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down && event.getRepeatCount() == 0) scroll(true);
                return true;

            default:
                return false;
        }
    }

    void toggle(boolean showToast) {
        active = !active;
        AppLog.w("MouseToggle active: " + active);
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

    private void clickAt(float x, float y) {
        AppLog.w("clickAt (" + x + ", " + y + ")");
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 50));
        service.dispatchGesture(builder.build(), new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                AppLog.w("clickAt completed");
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                AppLog.w("clickAt cancelled");
            }
        }, handler);
    }

    private void scroll(boolean down) {
        Path scrollPath = new Path();
        float startY = down ? screenHeight * 0.72f : screenHeight * 0.28f;
        float endY = down ? screenHeight * 0.28f : screenHeight * 0.72f;
        float midX = screenWidth * 0.5f;
        scrollPath.moveTo(midX, startY);
        scrollPath.lineTo(midX, endY);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(scrollPath, 0, 200));
        service.dispatchGesture(builder.build(), null, null);
    }

    void destroy() {
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
