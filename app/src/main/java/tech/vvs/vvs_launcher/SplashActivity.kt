package tech.vvs.vvs_launcher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple splash screen activity that displays the VVS logo on launch. After
 * a short delay, it navigates to [MainActivity] and finishes itself. This
 * prevents the logo from occupying the player screen during playback.
 */
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        // Hide the action bar on the splash screen to allow the logo to take
        // the full centre area without UI chrome.
        supportActionBar?.hide()
        // Navigate to the main activity after a 2 second delay
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }
}