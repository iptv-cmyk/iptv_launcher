package tech.vvs.vvs_launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class HomeButtonAccessibilityService extends AccessibilityService {

    private static final String TAG = "HomeButtonService";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed for key interception
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
        return super.onKeyEvent(event);
    }

    private void launchApp() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }
}
