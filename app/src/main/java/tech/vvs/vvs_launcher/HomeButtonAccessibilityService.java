package tech.vvs.vvs_launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class HomeButtonAccessibilityService extends AccessibilityService {

    private static final String TAG = "HomeButtonService";
    private static final String ACTION_SHOW_WELCOME = "tech.vvs.vvs_launcher.action.SHOW_WELCOME";
    private static final String ACTION_OPEN_ABOUT = "tech.vvs.vvs_launcher.action.OPEN_ABOUT";
    private static final String PREFS_NAME = "vvs_prefs";
    private static final String PREF_PENDING_HOME_RESET = "pending_home_reset";
    private static final String EXTRA_OPEN_ABOUT = "open_about";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op: shade dismissal is handled via timed callbacks in scheduleShadeDismiss()
    }

    @Override
    public void onInterrupt() {
        // Not needed
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Intercepted Home button press");
                launchApp();
            }
            return true; // Consume the event
        }
        // Dcolor settings button reports keycode 83, which is KEYCODE_NOTIFICATION.
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_NOTIFICATION || keyCode == 83) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Intercepted Settings button press");
                // Dismiss the Settings panel first, then open about.
                // dismissSettingsPanel() will also re-raise the About screen at 600ms
                // to ensure it wins over any residual system UI.
                dismissSettingsPanel();
                openAboutInApp();
            }
            // Consume both ACTION_DOWN and ACTION_UP to prevent the system from
            // acting on the key release event as well.
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_SLEEP || keyCode == KeyEvent.KEYCODE_SOFT_SLEEP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Intercepted Power/Sleep button press");
                launchApp();
            }
            return false; // Let the system handle it and send poweroff to the TV via HDMI-CEC
        }
        return super.onKeyEvent(event);
    }

    private void launchApp() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_PENDING_HOME_RESET, true)
                .apply();

        Intent showWelcomeIntent = new Intent(ACTION_SHOW_WELCOME);
        showWelcomeIntent.setPackage(getPackageName());
        sendBroadcast(showWelcomeIntent);

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    private void openAboutInApp() {
        Log.d(TAG, "open about via accessibility service");
        Intent openAboutIntent = new Intent(ACTION_OPEN_ABOUT);
        openAboutIntent.setPackage(getPackageName());
        sendBroadcast(openAboutIntent);

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(EXTRA_OPEN_ABOUT, true);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        startActivity(intent);
    }

    private void dismissSettingsPanel() {
        // Cancel any previously queued dismiss calls.
        mainHandler.removeCallbacksAndMessages(null);

        // The Settings button opens a system Settings Activity (side panel), not just the
        // notification shade. GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE has no effect on it.
        // We must use GLOBAL_ACTION_BACK to close it, fired several times to be reliable
        // across different device speeds.
        for (long delayMs : new long[]{0, 100, 250, 500, 900}) {
            mainHandler.postDelayed(this::pressBack, delayMs);
        }

        // After we've pushed the settings panel away, re-raise our About screen
        // so it ends up on top of any residual system UI.
        mainHandler.postDelayed(this::openAboutInApp, 600);
    }

    private void pressBack() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to press back", t);
        }
    }
}
