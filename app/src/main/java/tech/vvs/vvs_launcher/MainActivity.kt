package tech.vvs.vvs_launcher

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dadb.Dadb
import dadb.AdbKeyPair
import java.io.File
import androidx.media3.common.PlaybackException
import android.util.Log
import org.json.JSONObject
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import java.util.regex.Pattern
import android.app.AlarmManager
import android.app.PendingIntent
import androidx.core.content.ContextCompat
import java.util.Calendar
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator

/**
 * Main activity hosting the video player and channel list.
 * INTEGRATED VERSION: Preserves UI logic while fixing memory leaks and stall recovery.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MainActivity : AppCompatActivity() {
    companion object {
        private const val ACTION_SHOW_WELCOME = "tech.vvs.vvs_launcher.action.SHOW_WELCOME"
        private const val ACTION_OPEN_ABOUT = "tech.vvs.vvs_launcher.action.OPEN_ABOUT"
        private const val PREF_PENDING_HOME_RESET = "pending_home_reset"
        private const val EXTRA_OPEN_ABOUT = "open_about"
    }

    private val channelAutoHideHandler = Handler(Looper.getMainLooper())
    private val channelAutoHideRunnable = Runnable {
        try {
            if (this@MainActivity::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        } catch (_: Throwable) {}
    }

    private lateinit var viewModel: ChannelViewModel
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var youTubePlayerView: YouTubePlayerView
    private var youTubePlayer: YouTubePlayer? = null
    private lateinit var backgroundView: ImageView
    private lateinit var emptyStateText: TextView
    private lateinit var waitingForChannelContainer: View
    private lateinit var waitingChannelIcon: ImageView
    private var waitingIconAnimator: ObjectAnimator? = null
    private var channelsButtonAnimator: ObjectAnimator? = null
    private lateinit var serviceDiscoveryProgress: android.widget.ProgressBar
    private lateinit var welcomeStateContainer: View
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var adapter: ChannelAdapter
    private lateinit var channelsButton: View
    private var dpadUpClickCount = 0
    private val resetDpadUpClickCounterRunnable = Runnable { dpadUpClickCount = 0 }
    private lateinit var multicastLock: WifiManager.MulticastLock
    
    private val osdHandler = Handler(Looper.getMainLooper())
    
    private lateinit var floatingButtonsContainer: View
    private val buttonsOverlayHandler = Handler(Looper.getMainLooper())
    private val hideFloatingButtonsRunnable = Runnable {
        if (::floatingButtonsContainer.isInitialized && (this@MainActivity::welcomeStateContainer.isInitialized && welcomeStateContainer.visibility != View.VISIBLE)) {
            floatingButtonsContainer.visibility = View.GONE
        }
    }
    
    private lateinit var gestureDetector: GestureDetector
    private var isFullScreen = false

    // --------------------------------------------------------------------------------
    // IMPROVED STALL CHECKER & RETRY POLICY
    // --------------------------------------------------------------------------------
    
    private val stallHandler = Handler(Looper.getMainLooper())
    private var lastPosCheckMs: Long = 0L
    private var lastPos: Long = 0L
    
    private val stallChecker = object : Runnable {
        override fun run() {
            if (!::player.isInitialized) return

            val now = android.os.SystemClock.elapsedRealtime()
            val pos = player.currentPosition
            val state = player.playbackState
            
            // Check if position hasn't moved for 5 seconds
            val positionStuck = (now - lastPosCheckMs) >= 5_000 && (pos - lastPos) < 200
            
            // We only care if we are in a state where we SHOULD be playing
            val isSupposedToPlay = (state == Player.STATE_BUFFERING || (state == Player.STATE_READY && player.playWhenReady))
            val notEnded = (state != Player.STATE_ENDED)
            
            if (positionStuck && isSupposedToPlay && notEnded) {
                // HARD FIX: Instead of setMediaItem (which reverts to default config),
                // we call restartCurrentStream() which preserves the UDP configuration.
                restartCurrentStream()
            }
            
            lastPosCheckMs = now
            lastPos = pos
            stallHandler.postDelayed(this, 2_000)
        }
    }

    private val infiniteRetryPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val count = info.errorCount.coerceAtLeast(1)
            // Cap retry delay at 5s to ensure quick recovery for live TV
            return (1_000L + (count - 1) * 500L).coerceAtMost(5_000L)
        }
    }

    private lateinit var recyclerView: RecyclerView
    private val backgroundAssetsDir = "backgrounds"
    private val backgroundPrefKey = "background_asset"
    private val roomNumberPrefKey = "room_number"
    private val techMenuUsedPrefKey = "tech_menu_used"
    private val prefs by lazy {
        getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
    }
    private val showWelcomeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SHOW_WELCOME) {
                clearPendingHomeReset()
                returnToWelcomeScreen()
            }
        }
    }
    private val openAboutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_OPEN_ABOUT) {
                openAboutDialogFromLauncher()
            }
        }
    }
    
    // Listener to reload channels if URL changes via service
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "channel_list_url") {
            val pendingUrl = sharedPreferences.getString("channel_list_url", null)
            if (!pendingUrl.isNullOrEmpty()) {
                Log.d("VVS_TV_LOG", "Preference changed: new channel list URL: $pendingUrl")
                viewModel.fetchChannelList(pendingUrl)
            }
        } else if (key == "ibs_ip" || key == "ibs_port" || key == backgroundPrefKey) {
            // Refresh background if dynamic changes or user setting checks
            if (key == backgroundPrefKey || prefs.getString(backgroundPrefKey, null).isNullOrEmpty()) {
                 applyBackgroundFromPrefs()
            }
        } else if (key == "last_discovery_time") {
            val url = prefs.getString("channel_list_url", null)
            // Only fetch if we have a URL AND our current channel list is empty.
            // This prevents reloading the list every 2 minutes when the discovery service pings.
            if (!url.isNullOrEmpty() && viewModel.channels.value.isEmpty()) {
                Log.d("VVS_TV_LOG", "Discovery timestamp updated and channels empty. Refreshing channels from: $url")
                viewModel.fetchChannelList(url)
            } else {
                 //Log.d("VVS_TV_LOG", "Discovery timestamp updated but channels already loaded. Skipping refresh.")
            }
        }
    }

    // --------------------------------------------------------------------------------
    // UI FUNCTIONS (Unchanged)
    // --------------------------------------------------------------------------------

    private fun enterFullscreen() {
        try {
            supportActionBar?.hide()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } catch (_: Throwable) { }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        // Back button logic preserved
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {

            override fun handleOnBackPressed() {
                if (this@MainActivity::drawerLayout.isInitialized &&
                    drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                    showFloatingButtonsTemporarily()
                    return
                }
              /*
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.exit_title)
                    .setMessage(R.string.exit_message)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                    .setNegativeButton(R.string.no, null)
                    .create()

                dialog.show()

                val positiveButtonColor = androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.colorPrimaryDialogButton)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(positiveButtonColor)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(positiveButtonColor)

               */
            }


        })


        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        // Critical for TV apps: Keep screen on via Window flag
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch(Dispatchers.IO) {
            // Save device info on startup
            DeviceManager.saveDeviceInfo(this@MainActivity)
            
            // Log App Start Event (safe to call here as ID is generated/retrieved above)
            val deviceId = DeviceManager.getOrCreateDeviceId(this@MainActivity)
            AnalyticsManager.logAppStart(this@MainActivity, deviceId)

            val roomNum = getRoomNumber()
            if (roomNum != "-1") {
                withContext(Dispatchers.Main) {
                    RegistrationService.register(this@MainActivity, roomNum.toString())
                }
            }
        }

        enterFullscreen()

        // Hide unnecessary controls
        runCatching {
            val pv = findViewById<PlayerView>(R.id.playerView)
            pv.findViewById<View>(androidx.media3.ui.R.id.exo_prev)?.visibility = View.GONE
            pv.findViewById<View>(androidx.media3.ui.R.id.exo_next)?.visibility = View.GONE
            pv.findViewById<View>(androidx.media3.ui.R.id.exo_rew)?.visibility = View.GONE
            pv.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd)?.visibility = View.GONE
        }

        backgroundView = findViewById(R.id.backgroundImage)

        // Multicast Lock
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("multicast_lock").apply {
            setReferenceCounted(true)
        }

        drawerLayout = findViewById(R.id.drawerLayout)
        // Drawer Logic Preserved
        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) = cancelChannelAutoHide()
            override fun onDrawerOpened(drawerView: View) {
                scheduleChannelAutoHide(4000)
                scrollToSelectedChannel()
            }
            override fun onDrawerClosed(drawerView: View) = cancelChannelAutoHide()
            override fun onDrawerStateChanged(newState: Int) {
                if (newState == DrawerLayout.STATE_IDLE && drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    scheduleChannelAutoHide(4000)
                }
            }
        })

        playerView = findViewById(R.id.playerView)
        youTubePlayerView = findViewById(R.id.youtubePlayerView)
        lifecycle.addObserver(youTubePlayerView)
        
        val osdText = findViewById<TextView>(R.id.osdText)
        emptyStateText = findViewById(R.id.emptyStateText)
        waitingForChannelContainer = findViewById(R.id.waitingForChannelContainer)
        waitingChannelIcon = findViewById(R.id.waitingChannelIcon)
        serviceDiscoveryProgress = findViewById(R.id.serviceDiscoveryProgress)
        welcomeStateContainer = findViewById(R.id.welcomeStateContainer)
        floatingButtonsContainer = findViewById(R.id.floatingButtonsContainer)
        val settingsButton = findViewById<View>(R.id.settingsButton)
        
        val techMenuUsed = prefs.getBoolean(techMenuUsedPrefKey, false)
        if (techMenuUsed) {
            settingsButton.visibility = View.GONE
        }
        
        channelsButton = findViewById(R.id.channelsButton)
        channelsButton.setOnClickListener {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START) && adapter.itemCount > 0) {
                drawerLayout.openDrawer(GravityCompat.START)
                scrollToSelectedChannel()
            }
        }
        findViewById<View>(R.id.youtubeButton).setOnClickListener {
            launchApp("com.google.android.youtube.tv", "https://www.youtube.com")
        }
        findViewById<View>(R.id.netflixButton).setOnClickListener {
            launchApp("com.netflix.ninja", "https://www.netflix.com")
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            if (this@MainActivity::floatingButtonsContainer.isInitialized) {
                floatingButtonsContainer.visibility = View.GONE
            }
            showTechDialog()
        }
        findViewById<View>(R.id.aboutButton).setOnClickListener {
            if (this@MainActivity::floatingButtonsContainer.isInitialized) {
                floatingButtonsContainer.visibility = View.GONE
            }
            showAboutDialog()
        }
        recyclerView = findViewById(R.id.channelRecyclerView)

        applyBackgroundFromPrefs()
        updateEmptyState(null)

        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        viewModel = ViewModelProvider(this)[ChannelViewModel::class.java]

        adapter = ChannelAdapter { channel ->
            viewModel.selectChannel(channel)
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Build Player with UPDATED configs
        player = buildPlayer()
        playerView.player = player
        playerView.useArtwork = false
        playerView.keepScreenOn = true
        player.setWakeMode(C.WAKE_MODE_NETWORK)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                Log.d("VVS_TV_LOG", "onSingleTapConfirmed")
                val hasMedia = this@MainActivity::player.isInitialized && player.mediaItemCount > 0
                if (hasMedia && (this@MainActivity::welcomeStateContainer.isInitialized && welcomeStateContainer.visibility == View.GONE)) {
                    if (this@MainActivity::floatingButtonsContainer.isInitialized && floatingButtonsContainer.visibility != View.VISIBLE) {
                        showFloatingButtonsTemporarily()
                        return true
                    }
                } else if (this@MainActivity::welcomeStateContainer.isInitialized && welcomeStateContainer.visibility == View.VISIBLE) {
                    if (this@MainActivity::floatingButtonsContainer.isInitialized && floatingButtonsContainer.visibility != View.VISIBLE) {
                        floatingButtonsContainer.visibility = View.VISIBLE
                        buttonsOverlayHandler.removeCallbacks(hideFloatingButtonsRunnable)
                        return true
                    }
                }
                return super.onSingleTapConfirmed(e)
            }
        })
        
        playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        lifecycleScope.launch {
            viewModel.channels.collect { channels ->
                adapter.submitList(channels)
                updateEmptyState(viewModel.selectedChannel.value)
                if (channels.isNotEmpty()) {
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                    
                    // Auto-Resume Removed per user request
                    /*
                    val lastUri = prefs.getString("last_uri", null)
                    if (lastUri != null) {
                        val current = viewModel.selectedChannel.value
                        if (current == null || current.uri != lastUri) {
                            val idx = channels.indexOfFirst { it.uri == lastUri }
                            if (idx >= 0) viewModel.selectChannel(channels[idx])
                        }
                    }
                    */
                } else {
                    drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.selectedChannel.collect { channel ->
                updateEmptyState(channel)
                adapter.updateSelection(channel?.uri) // Highlight in UI
                channel?.let {
                    playChannel(it, osdText)
                }
            }
        }

        val savedUrl = prefs.getString("channel_list_url", null)
        val reloadOnStart = prefs.getBoolean("reload_on_start", false)
        if (!savedUrl.isNullOrEmpty() && (reloadOnStart || viewModel.channels.value.isEmpty())) {
            viewModel.fetchChannelList(savedUrl)
        }

        // Register listener for dynamic updates from service
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ContextCompat.registerReceiver(
            this,
            showWelcomeReceiver,
            IntentFilter(ACTION_SHOW_WELCOME),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            openAboutReceiver,
            IntentFilter(ACTION_OPEN_ABOUT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        // Ensure Netflix auto-reset is scheduled
        scheduleNetflixReset(this)

        // Start services in background to prevent ANR when system_server is busy
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d("VVS_TV_LOG", "Starting IBSDiscoveryService...")
            try {
                startService(Intent(this@MainActivity, IBSDiscoveryService::class.java))
            } catch (e: Exception) {
                Log.e("VVS_TV_LOG", "Failed to start IBSDiscoveryService", e)
            }

            val helloIntent = Intent(this@MainActivity, HelloService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(helloIntent)
            } else {
                startService(helloIntent)
            }
        }

        // Request Notification Permission for Android 13+
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        if (consumeOpenAboutIntent(intent)) {
            openAboutDialogFromLauncher()
        }
    }

    /**
     * Helper to get the discovered player URL.
     */
    fun getPlayerURL(): String? {
        // PREF_NAME is "vvs_prefs"
        return prefs.getString("channel_list_url", null)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (this@MainActivity::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) scheduleChannelAutoHide(4000)
    }



    private fun showFloatingButtonsTemporarily() {
        if (!::floatingButtonsContainer.isInitialized) return
        floatingButtonsContainer.visibility = View.VISIBLE
        val channelsBtn = findViewById<View>(R.id.channelsButton)
        channelsBtn?.post { channelsBtn.requestFocus() }
        buttonsOverlayHandler.removeCallbacks(hideFloatingButtonsRunnable)
        buttonsOverlayHandler.postDelayed(hideFloatingButtonsRunnable, 10_000)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN || event.action == android.view.KeyEvent.ACTION_UP) {
            val k = event.keyCode
            val isZap = k == android.view.KeyEvent.KEYCODE_CHANNEL_UP ||
                         k == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN ||
                         k == android.view.KeyEvent.KEYCODE_MEDIA_NEXT ||
                         k == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                         k == android.view.KeyEvent.KEYCODE_PAGE_UP ||
                         k == android.view.KeyEvent.KEYCODE_PAGE_DOWN ||
                         k == 166 || k == 167
            if (this@MainActivity::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) scheduleChannelAutoHide(4000)
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            // Reset overlay timer if buttons are visible and user navigates
            if (this@MainActivity::floatingButtonsContainer.isInitialized && floatingButtonsContainer.visibility == View.VISIBLE) {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || 
                    event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP ||
                    event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                    buttonsOverlayHandler.removeCallbacks(hideFloatingButtonsRunnable)
                    buttonsOverlayHandler.postDelayed(hideFloatingButtonsRunnable, 10_000)
                }
            }

            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                dpadUpClickCount++
                buttonsOverlayHandler.removeCallbacks(resetDpadUpClickCounterRunnable)
                buttonsOverlayHandler.postDelayed(resetDpadUpClickCounterRunnable, 3000)
                if (dpadUpClickCount >= 5) {
                    dpadUpClickCount = 0
                    showExitDialog()
                    return true
                }
            }

            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val hasMedia = this@MainActivity::player.isInitialized && player.mediaItemCount > 0
                    if (hasMedia && (this@MainActivity::welcomeStateContainer.isInitialized && welcomeStateContainer.visibility == View.GONE)) {
                        if (floatingButtonsContainer.visibility != View.VISIBLE) {
                            showFloatingButtonsTemporarily()
                            return true
                        }
                    }
                }
                android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_PAGE_UP,
                166 -> {
                    selectNextChannel()
                    return true
                }
                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                167 -> {
                    selectPreviousChannel()
                    return true
                }
                android.view.KeyEvent.KEYCODE_SETTINGS,
                android.view.KeyEvent.KEYCODE_MENU -> {
                    if (this@MainActivity::floatingButtonsContainer.isInitialized) {
                        floatingButtonsContainer.visibility = View.GONE
                    }
                    showAboutDialog()
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val hasMedia = this@MainActivity::player.isInitialized && player.mediaItemCount > 0
                    if (hasMedia && 
                        (this@MainActivity::welcomeStateContainer.isInitialized && welcomeStateContainer.visibility == View.GONE) &&
                        (this@MainActivity::floatingButtonsContainer.isInitialized && floatingButtonsContainer.visibility != View.VISIBLE)) {
                        
                        if (this@MainActivity::drawerLayout.isInitialized && !drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START) && adapter.itemCount > 0) {
                            drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
                            scrollToSelectedChannel()
                            return true
                        }
                    }
                    // Standard focus traversal handles moving left between buttons.
                    return super.dispatchKeyEvent(event)
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Only intercept to close drawer if it is open
                    // Otherwise let standard focus traversal handle moving right between the buttons
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scrollToSelectedChannel() {
        val currentChannel = viewModel.selectedChannel.value
        val channels = viewModel.channels.value
        
        recyclerView.post {
            if (currentChannel != null && channels.isNotEmpty()) {
                val index = channels.indexOfFirst { it.uri == currentChannel.uri }
                if (index != -1) {
                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
                    // Request focus after scroll
                    recyclerView.postDelayed({
                        val viewHolder = recyclerView.findViewHolderForAdapterPosition(index)
                        viewHolder?.itemView?.requestFocus()
                    }, 50) 
                } else {
                    // Fallback to top if not found
                    if (recyclerView.childCount > 0) recyclerView.getChildAt(0).requestFocus()
                }
            } else {
                // Fallback to top if nothing selected
                if (recyclerView.childCount > 0) recyclerView.getChildAt(0).requestFocus()
            }
        }
    }

    private fun scheduleChannelAutoHide(timeoutMs: Long = 4000) {
        channelAutoHideHandler.removeCallbacks(channelAutoHideRunnable)
        channelAutoHideHandler.postDelayed(channelAutoHideRunnable, timeoutMs)
    }

    private fun cancelChannelAutoHide() {
        channelAutoHideHandler.removeCallbacks(channelAutoHideRunnable)
    }

    private fun selectNextChannel() {
        val list = viewModel.channels.value
        val current = viewModel.selectedChannel.value
        val index = current?.let { list.indexOf(it) } ?: -1
        if (index != -1 && list.isNotEmpty()) {
            var nextIndex = (index + 1) % list.size
            var attempts = 0
            while (list[nextIndex].uri == "udp://@0.0.0.0" && attempts < list.size) {
                nextIndex = (nextIndex + 1) % list.size
                attempts++
            }
            if (attempts < list.size) {
                viewModel.selectChannel(list[nextIndex])
            }
        }
    }

    private fun selectPreviousChannel() {
        val list = viewModel.channels.value
        val current = viewModel.selectedChannel.value
        val index = current?.let { list.indexOf(it) } ?: -1
        if (index != -1 && list.isNotEmpty()) {
            var prevIndex = (index - 1 + list.size) % list.size
            var attempts = 0
            while (list[prevIndex].uri == "udp://@0.0.0.0" && attempts < list.size) {
                prevIndex = (prevIndex - 1 + list.size) % list.size
                attempts++
            }
            if (attempts < list.size) {
                viewModel.selectChannel(list[prevIndex])
            }
        }
    }

    // --------------------------------------------------------------------------------
    // PLAYER CONFIGURATION (UPDATED)
    // --------------------------------------------------------------------------------

    private fun buildPlayer(): ExoPlayer {
        val userMinBufferMs = prefs.getInt("min_buffer_ms", -1)
        val minBufferMs = if (userMinBufferMs > 0) userMinBufferMs else 15_000
        val maxBufferMs = minBufferMs * 4
        
        val playbackMs = minOf(2_500, minBufferMs)
        val rebufferMs = minOf(5_000, minBufferMs)
        
        // OPTIMIZED LOAD CONTROL
        // prioritizeTimeOverSizeThresholds(true) is crucial for preventing OOM on Android TV
        // during long playback sessions.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                playbackMs,
                rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true) 
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        // Default factory for non-UDP content
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(infiniteRetryPolicy)

        return ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d("VVS_TV_LOG", "onIsPlayingChanged: $isPlaying")
                        // Double check wake lock behavior
                        if (isPlaying) {
                           window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                           // Optional: clear flag if desired, but for TV we usually keep it
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateString = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        Log.d("VVS_TV_LOG", "Playback State: $stateString")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VVS_TV_LOG", "Player Error: ${error.message}", error)
                    }
                })
            }
    }

    /**
     * Helper to create the correct MediaSource. 
     * This ensures both playChannel() and stallChecker use the HIGH PERFORMANCE UDP config.
     */
    private fun createMediaSourceFor(uri: Uri): MediaSource {
        val isUdp = uri.scheme?.startsWith("udp", ignoreCase = true) == true
        
        return if (isUdp) {
             // PROFESSIONAL UDP SETUP
            val udpFactory = DataSource.Factory {
                UdpDataSource(3000, 100_000) // 3s timeout, 100KB packets
            }
            val tsExtractorFactory = ExtractorsFactory {
                arrayOf(
                    TsExtractor(
                        TsExtractor.MODE_SINGLE_PMT,
                        TimestampAdjuster(0),
                        DefaultTsPayloadReaderFactory()
                    )
                )
            }
            ProgressiveMediaSource.Factory(udpFactory, tsExtractorFactory)
                .setLoadErrorHandlingPolicy(infiniteRetryPolicy)
                .createMediaSource(MediaItem.fromUri(uri))
        } else {
            // Standard HLS/HTTP
            val defaultFactory = DefaultDataSource.Factory(this)
             DefaultMediaSourceFactory(defaultFactory)
                .setLoadErrorHandlingPolicy(infiniteRetryPolicy)
                .createMediaSource(MediaItem.fromUri(uri))
        }
    }

    private fun playChannel(channel: Channel, osdText: TextView?) {
        Log.d("VVS_TV_LOG", "Playing channel: ${channel.name}, URI: ${channel.uri}")

        // Special handling for YouTube URLs
        if (channel.uri.contains("youtube.com") || channel.uri.contains("youtu.be")) {
             try {
                 val videoId = extractVideoIdFromUrl(channel.uri)
                 Log.d("VVS_TV_LOG", "Extracted YouTube ID: $videoId from ${channel.uri}")
                 
                 if (videoId != null) {
                     // Switch to YouTube Player
                     player.stop()
                     playerView.visibility = View.GONE
                     
                     // Ensure view is visible before loading
                     youTubePlayerView.visibility = View.VISIBLE
                     backgroundView.visibility = View.GONE 
                     
                     // Debug the ID characters
                     // val hexString = videoId.map { "0x%02X".format(it.code) }.joinToString(" ")
                     // Log.d("VVS_TV_LOG", "Cleaned ID: '$videoId', Length: ${videoId.length}, Hex: $hexString")

                     if (youTubePlayer != null) {
                         Log.d("VVS_TV_LOG", "YouTubePlayer already executing, loading video: $videoId")
                         youTubePlayer?.loadVideo(videoId, 0f)
                     } else {
                         Log.d("VVS_TV_LOG", "YouTubePlayer not ready, initializing...")
                         
                         val listener = object : AbstractYouTubePlayerListener() {
                             override fun onReady(p: YouTubePlayer) {
                                 Log.d("VVS_TV_LOG", "YouTubePlayer onReady. Loading video: $videoId")
                                 youTubePlayer = p
                                 p.loadVideo(videoId, 0f)
                             }

                             override fun onStateChange(youTubePlayer: YouTubePlayer, state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState) {
                                 Log.d("VVS_TV_LOG", "YouTubePlayer State Change: $state")
                             }

                             override fun onError(p: YouTubePlayer, error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError) {
                                 Log.e("VVS_TV_LOG", "YouTubePlayer Error: $error")
                                 Toast.makeText(this@MainActivity, "YouTube Error: $error", Toast.LENGTH_LONG).show()
                                 
                                 // Fallback: If not playable embedded, open in YouTube App
                                 if (error == com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER) {
                                      Log.d("VVS_TV_LOG", "Opening in external app due to restriction")
                                      try {
                                          val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
                                          startActivity(intent)
                                      } catch (e: Exception) {
                                          val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                                          startActivity(webIntent)
                                      }
                                 }
                             }
                         }

                         // Configure IFrame options with Origin to allow some restricted videos
                         val options = com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions.Builder()
                             .controls(1)
                             .rel(0)
                             .ivLoadPolicy(3)
                             .ccLoadPolicy(1)
                             .origin("https://www.youtube.com") 
                             .build()

                         youTubePlayerView.initialize(listener, options)
                     }
                     rememberLastChannel(channel)
                     return
                 } else {
                     Log.e("VVS_TV_LOG", "Failed to extract YouTube ID from URL: ${channel.uri}")
                     Toast.makeText(this, "Invalid YouTube URL", Toast.LENGTH_SHORT).show()
                     return
                 }
             } catch (e: Exception) {
                 Log.e("VVS_TV_LOG", "Error playing YouTube video", e)
                 Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                 return
             }
        }

        // Standard Playback (ExoPlayer)
        // Ensure YouTube player is hidden/paused
        youTubePlayerView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        youTubePlayer?.pause()

        val uri = Uri.parse(channel.uri)
        val isUdp = uri.scheme?.startsWith("udp", ignoreCase = true) == true

        // Lock Management
        if (isUdp) {
            if (!multicastLock.isHeld) multicastLock.acquire()
        } else {
            if (multicastLock.isHeld) multicastLock.release()
        }

        player.stop()

        // Use the centralized helper to ensure consistent configuration
        val mediaSource = createMediaSourceFor(uri)
        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()

        // Stall Checker Reset
        lastPosCheckMs = android.os.SystemClock.elapsedRealtime()
        lastPos = player.currentPosition
        stallHandler.removeCallbacks(stallChecker)
        stallHandler.postDelayed(stallChecker, 2_000)

        rememberLastChannel(channel)
        
        // Show OSD
        if (osdText != null) {
            val displayName = if (channel.number != null) "${channel.number}. ${channel.name}" else channel.name
            osdText.text = displayName
            osdText.visibility = View.VISIBLE
            osdHandler.removeCallbacksAndMessages(null)
            osdHandler.postDelayed({ osdText.visibility = View.GONE }, 3_000)
        }
    }

    private fun extractVideoIdFromUrl(url: String): String? {
        try {
            // Clean up the URL first
            val cleanUrl = url.trim()
            
            // Regex to strictly match exactly 11 characters (alphanumeric, -, _) 
            // preceded by common YouTube ID delimiters.
            // We do NOT use [^#&?] because it might be too greedy or miss malformed cases.
            val pattern = "(?<=watch\\?v=|/videos/|embed\\/|youtu.be\\/|\\/v\\/|\\/e\\/|watch\\?v%3D|watch\\?feature=player_embedded&v=|%2Fvideos%2F|embed%\u200C\u200B2F|youtu.be%2F|%2Fv%2F|/live/)([a-zA-Z0-9_-]{11})"
            val compiledPattern = Pattern.compile(pattern)
            val matcher = compiledPattern.matcher(cleanUrl)
            
            if (matcher.find()) {
                val id = matcher.group(1)
                return id
            }
            
            return null
        } catch (e: Exception) {
            Log.e("VVS_TV_LOG", "ID Extraction failed", e)
            return null
        }
    } 
    /**
     * Called by stallChecker to fix playback without breaking the UDP configuration.
     */
    private fun restartCurrentStream() {
        val currentChannel = viewModel.selectedChannel.value ?: return
        // We pass null for OSD so it doesn't flash the name continuously during network glitches
        playChannel(currentChannel, null) 
    }

    // --------------------------------------------------------------------------------
    // DIALOGS
    // --------------------------------------------------------------------------------

    private fun showPlaySingleDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.enter_stream_url)
            setText("udp://@225.0.0.1:9001")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_play_single)
            .setView(editText)
            .setPositiveButton(R.string.load) { _, _ ->
                val url = editText.text.toString().trim()
                if (url.isNotEmpty()) {
                    Log.d("VVS_TV_LOG", "Loading single URL: $url")
                    val channel = Channel(name = url, uri = url, number = null)
                    viewModel.selectChannel(channel)
                } else {
                    Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        val versionText = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val verCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "Version Code: $verCode"
        } catch (e: Exception) {
            "Version Code: Unknown"
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val versionButton = dialogView.findViewById<android.widget.Button>(R.id.versionButton)
        versionButton.text = versionText

        var tapCount = 0
        val resetTapRunnable = Runnable { tapCount = 0 }
        val tapHandler = Handler(Looper.getMainLooper())

        versionButton.setOnClickListener {
            tapCount++
            tapHandler.removeCallbacks(resetTapRunnable)
            tapHandler.postDelayed(resetTapRunnable, 3000)
            if (tapCount >= 7) {
                tapCount = 0
                prefs.edit().putBoolean(techMenuUsedPrefKey, false).apply()
                findViewById<View>(R.id.settingsButton).visibility = View.VISIBLE
                Toast.makeText(this, R.string.tech_menu_unlocked, Toast.LENGTH_SHORT).show()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok, null)
            .create()

        dialog.setOnDismissListener {
            if (this@MainActivity::welcomeStateContainer.isInitialized && welcomeStateContainer.visibility == View.VISIBLE) {
                if (this@MainActivity::floatingButtonsContainer.isInitialized) {
                    floatingButtonsContainer.visibility = View.VISIBLE
                    buttonsOverlayHandler.removeCallbacks(hideFloatingButtonsRunnable)
                }
            }
        }

        dialog.show()

        val positiveButtonColor = ContextCompat.getColor(this, R.color.colorPrimaryDialogButton)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(positiveButtonColor)


    }

    private fun openAboutDialogFromLauncher() {
        if (this@MainActivity::floatingButtonsContainer.isInitialized) {
            floatingButtonsContainer.visibility = View.GONE
        }
        showAboutDialog()
    }

    private fun launchApp(packageName: String, fallbackUrl: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
            } catch (e: Exception) {
                Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateEmptyState(channel: Channel?) {
        if (!this@MainActivity::emptyStateText.isInitialized) return
        val hasMedia = this@MainActivity::player.isInitialized && player.mediaItemCount > 0
        val show = channel == null && !hasMedia
        
        if (show) {
            if (::playerView.isInitialized) {
                playerView.visibility = View.GONE
            }
            if (::youTubePlayerView.isInitialized) {
                youTubePlayerView.visibility = View.GONE
            }
            val channels = if (::viewModel.isInitialized) viewModel.channels.value else emptyList()
            if (channels.isEmpty()) {
                waitingForChannelContainer.visibility = View.GONE
                serviceDiscoveryProgress.visibility = View.VISIBLE
                waitingIconAnimator?.cancel()
                stopChannelsButtonAnimation()
            } else {
                waitingForChannelContainer.visibility = View.VISIBLE
                serviceDiscoveryProgress.visibility = View.GONE
                emptyStateText.setText(R.string.waiting_for_channel)
                startWaitingIconAnimation()
                startChannelsButtonAnimation()
            }
            welcomeStateContainer.visibility = View.VISIBLE
            floatingButtonsContainer.visibility = View.VISIBLE
            buttonsOverlayHandler.removeCallbacks(hideFloatingButtonsRunnable)
            
            // Explicitly request focus for TV users so the D-pad is immediately ready to traverse
            channelsButton.post { channelsButton.requestFocus() }
            
        } else {
            waitingIconAnimator?.cancel()
            stopChannelsButtonAnimation()
            if (::playerView.isInitialized) {
                playerView.visibility = View.VISIBLE
            }
            welcomeStateContainer.visibility = View.GONE
            floatingButtonsContainer.visibility = View.GONE
        }
    }

    private fun returnToWelcomeScreen() {
        if (!::player.isInitialized || !::welcomeStateContainer.isInitialized) return

        cancelChannelAutoHide()
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        osdHandler.removeCallbacksAndMessages(null)
        findViewById<TextView>(R.id.osdText)?.visibility = View.GONE

        stallHandler.removeCallbacks(stallChecker)
        player.pause()
        player.clearMediaItems()

        youTubePlayer?.pause()
        if (::youTubePlayerView.isInitialized) {
            youTubePlayerView.visibility = View.GONE
        }
        if (multicastLock.isHeld) {
            multicastLock.release()
        }

        applyBackgroundFromPrefs()
        viewModel.clearSelection()
        updateEmptyState(null)
    }

    private fun consumePendingHomeReset(): Boolean {
        val pending = prefs.getBoolean(PREF_PENDING_HOME_RESET, false)
        if (pending) {
            clearPendingHomeReset()
        }
        return pending
    }

    private fun clearPendingHomeReset() {
        prefs.edit().remove(PREF_PENDING_HOME_RESET).apply()
    }

    private fun consumeOpenAboutIntent(intent: Intent?): Boolean {
        val shouldOpenAbout = intent?.getBooleanExtra(EXTRA_OPEN_ABOUT, false) == true
        if (shouldOpenAbout) {
            intent?.removeExtra(EXTRA_OPEN_ABOUT)
        }
        return shouldOpenAbout
    }

    private fun startWaitingIconAnimation() {
        if (!::waitingChannelIcon.isInitialized) return
        
        if (waitingIconAnimator == null) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.25f, 1.0f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.25f, 1.0f)
            waitingIconAnimator = ObjectAnimator.ofPropertyValuesHolder(waitingChannelIcon, scaleX, scaleY).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }
        }
        
        if (waitingIconAnimator?.isStarted != true) {
            waitingIconAnimator?.start()
        }
    }

    private fun startChannelsButtonAnimation() {
        if (!::channelsButton.isInitialized) return

        if (channelsButtonAnimator == null) {
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.25f, 1.0f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.25f, 1.0f)
            channelsButtonAnimator = ObjectAnimator.ofPropertyValuesHolder(channelsButton, scaleX, scaleY).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
            }
        }

        if (channelsButtonAnimator?.isStarted != true) {
            channelsButtonAnimator?.start()
        }
    }

    private fun stopChannelsButtonAnimation() {
        channelsButtonAnimator?.cancel()
        if (::channelsButton.isInitialized) {
            channelsButton.scaleX = 1.0f
            channelsButton.scaleY = 1.0f
        }
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                finishAffinity()
            }
            .setNegativeButton(R.string.no, null)
            .create()
            .also { confirmDialog ->
                confirmDialog.show()
                val btnColor = ContextCompat.getColor(this, R.color.colorPrimaryDialogButton)
                confirmDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(btnColor)
                confirmDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(btnColor)
            }
    }

    private fun showTechDialog() {
    val dialogView = layoutInflater.inflate(R.layout.dialog_tech, null)
    
    val backgroundsMissingText = dialogView.findViewById<TextView>(R.id.backgroundsMissingText)
    val listView = dialogView.findViewById<ListView>(R.id.backgroundListView)
    val roomEdit = dialogView.findViewById<EditText>(R.id.roomNumberEdit)
    val urlEdit = dialogView.findViewById<EditText>(R.id.channelUrlEdit)
    val bufferEdit = dialogView.findViewById<EditText>(R.id.bufferEdit)
    val netflixResetCheck = dialogView.findViewById<android.widget.CheckBox>(R.id.netflixResetCheck)
    val netflixResetHourEdit = dialogView.findViewById<EditText>(R.id.netflixResetHourEdit)
    val manualOverrideCheck = dialogView.findViewById<android.widget.CheckBox>(R.id.manualOverrideCheck)
    
    val assetNames = try {
        assets.list(backgroundAssetsDir)?.sorted()
    } catch (_: Exception) {
        null
    }
    
    if (assetNames.isNullOrEmpty()) {
        backgroundsMissingText.visibility = View.VISIBLE
        listView.visibility = View.GONE
    } else {
        val options = ArrayList<String>(assetNames.size + 1)
        options.add(getString(R.string.background_none))
        options.addAll(assetNames)
        val current = prefs.getString(backgroundPrefKey, null)
        val selected = if (current == null) 0 else assetNames.indexOf(current).let { idx ->
            if (idx >= 0) idx + 1 else 0
        }
        
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        listView.adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_list_item_single_choice,
            options
        )
        listView.setItemChecked(selected, true)
    }

    // Initialize values from preferences
    getRoomNumber()?.let { roomEdit.setText(it) }
    
    val savedUrl = prefs.getString("channel_list_url", null)
    urlEdit.setText(savedUrl ?: getString(R.string.default_channel_list_url))
    
    val savedBuffer = prefs.getInt("min_buffer_ms", -1)
    if (savedBuffer > 0) bufferEdit.setText(savedBuffer.toString())
    
    netflixResetCheck.isChecked = prefs.getBoolean("netflix_reset_enabled", true)
    val savedHour = prefs.getInt("netflix_reset_hour", 3)
    netflixResetHourEdit.setText(savedHour.toString())
    
    manualOverrideCheck.isChecked = prefs.getBoolean("is_channel_url_manual_override", false)

    val netflixResetNowButton = dialogView.findViewById<android.widget.Button>(R.id.netflixResetNowButton)
    netflixResetNowButton.setOnClickListener {
        Toast.makeText(this@MainActivity, "Attempting Netflix Reset...", Toast.LENGTH_SHORT).show()
        Thread {
            performAdbResetNetflix()
        }.start()
    }

    val exitAppButton = dialogView.findViewById<android.widget.Button>(R.id.exitAppButton)
    exitAppButton.setOnClickListener {
        showExitDialog()
    }

    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.action_tech)
        .setView(dialogView)
        .setPositiveButton(R.string.save, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    dialog.setOnShowListener {
            val color = ContextCompat.getColor(applicationContext, R.color.colorPrimaryDialogButton)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(color)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val roomValue = roomEdit.text.toString().trim()
                val roomNumber = roomValue
                if (roomNumber == null || roomNumber == "") {
                    Toast.makeText(this, R.string.invalid_room_number, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // Validate Netflix Hour
                val hourStr = netflixResetHourEdit.text.toString().trim()
                val hour = hourStr.toIntOrNull()
                if (netflixResetCheck.isChecked && (hour == null || hour !in 0..23)) {
                     Toast.makeText(this, "Invalid Reset Hour (0-23)", Toast.LENGTH_SHORT).show()
                     return@setOnClickListener
                }

                setRoomNumber(roomNumber)

                // Register with IBS
                RegistrationService.register(this@MainActivity, roomNumber)
                
                // Save Netflix Settings
                prefs.edit().apply {
                    putBoolean("netflix_reset_enabled", netflixResetCheck.isChecked)
                    if (hour != null) putInt("netflix_reset_hour", hour)
                }.apply()
                
                // Save Channel List URL and Buffer
                val url = urlEdit.text.toString().trim()
                val bufferText = bufferEdit.text.toString().trim()
                var minBufferMs: Int? = null
                if (bufferText.isNotEmpty()) {
                    try {
                        minBufferMs = bufferText.toInt()
                        if (minBufferMs!! <= 0) minBufferMs = null
                    } catch (e: NumberFormatException) {
                        minBufferMs = null
                    }
                }
                
                if (url.isNotEmpty() && !url.equals("0.0.0.0")) {
                    prefs.edit().apply {
                        putString("channel_list_url", url)
                        putBoolean("reload_on_start", true)
                        putBoolean("is_channel_url_manual_override", manualOverrideCheck.isChecked) 
                        if (minBufferMs != null) putInt("min_buffer_ms", minBufferMs!!)
                        else remove("min_buffer_ms")
                    }.apply()
                    // Fetch channels (will trigger preference listener if URL changed)
                    viewModel.fetchChannelList(url)
                } else {
                    Log.d("VVS_TV_LOG", "is_channel_url_manual_override false")
                    prefs.edit().apply {
                        remove("channel_list_url")
                        // If user clears the URL, we should probably default to NOT manual 
                        // unless they explicitly checked the box.
                        putBoolean("is_channel_url_manual_override", manualOverrideCheck.isChecked)
                        putBoolean("reload_on_start", true)
                        if (minBufferMs != null) putInt("min_buffer_ms", minBufferMs!!)
                        else remove("min_buffer_ms")
                    }.apply()
                    viewModel.fetchChannelList("")
                    startService(Intent(this@MainActivity, IBSDiscoveryService::class.java))
                    Toast.makeText(this@MainActivity, "Discovery restarted", Toast.LENGTH_SHORT).show()
                }
                
                // Update Schedule
                scheduleNetflixReset(this@MainActivity)

                val checked = listView.checkedItemPosition
                if (checked <= 0) {
                    prefs.edit().remove(backgroundPrefKey).apply()
                    applyBackgroundFromPrefs()
                } else {
                    val assetName = assetNames!![checked - 1]
                    prefs.edit().putString(backgroundPrefKey, assetName).apply()
                    applyBackgroundFromAsset(assetName)
                }
                // Save Settings
                prefs.edit().putBoolean(techMenuUsedPrefKey, true).apply()
                findViewById<View>(R.id.settingsButton).visibility = View.GONE
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (this@MainActivity::welcomeStateContainer.isInitialized && welcomeStateContainer.visibility == View.VISIBLE) {
                if (this@MainActivity::floatingButtonsContainer.isInitialized) {
                    floatingButtonsContainer.visibility = View.VISIBLE
                    buttonsOverlayHandler.removeCallbacks(hideFloatingButtonsRunnable)
                }
            }
        }
        dialog.show()
    }


    private fun applyBackgroundFromPrefs() {
        val assetName = prefs.getString(backgroundPrefKey, null)
        if (!assetName.isNullOrEmpty()) {
            applyBackgroundFromAsset(assetName)
        } else {
            // Check for IBS Dynamic Background
            val ibsIp = prefs.getString("ibs_ip", null)
            val ibsPort = prefs.getInt("ibs_port", -1)
            if (!ibsIp.isNullOrEmpty() && ibsPort > 0) {
                 applyIBSBackground(ibsIp, ibsPort)
            } else {
                 applyBackgroundFromAsset(null)
            }
        }
    }

    private fun applyIBSBackground(ip: String, port: Int) {
        if (!this@MainActivity::backgroundView.isInitialized) return
        
        val urlString = "http://$ip:$port/player/getwelcomepage"
        Log.d("VVS_TV_LOG", "Fetching dynamic background: $urlString")
        
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                java.net.URL(urlString).openStream().use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()

            withContext(Dispatchers.Main) {
                 if (bitmap != null) {
                     backgroundView.setImageBitmap(bitmap)
                     backgroundView.visibility = View.VISIBLE
                 } else {
                     Log.e("VVS_TV_LOG", "Failed to load IBS background from $urlString")
                     // Only hide if we are not already showing something valid 
                     // OR should we clear? Let's just keep previous or clear if explicitly needed.
                     // If this was a fresh start, backgroundView might be GONE.
                     if (backgroundView.drawable == null) {
                        backgroundView.visibility = View.GONE
                     }
                 }
            }
        }
    }

    fun getRoomNumber(): String? {
        val stored = prefs.getString(roomNumberPrefKey, "-1")
        return stored
    }

    fun setRoomNumber(roomNumber: String?) {
        prefs.edit().apply {
            if (roomNumber != null ) {
                putString(roomNumberPrefKey, roomNumber)
            } else {
                remove(roomNumberPrefKey)
            }
        }.apply()
    }

    private fun applyBackgroundFromAsset(assetName: String?) {
        if (!this@MainActivity::backgroundView.isInitialized) return
        if (assetName.isNullOrBlank()) {
            backgroundView.setImageDrawable(null)
            backgroundView.visibility = View.GONE
            return
        }
        val path = "$backgroundAssetsDir/$assetName"
        val drawable = runCatching {
            assets.open(path).use { stream ->
                android.graphics.drawable.Drawable.createFromStream(stream, assetName)
            }
        }.getOrNull()
        if (drawable == null) {
            backgroundView.setImageDrawable(null)
            backgroundView.visibility = View.GONE
            Toast.makeText(this, R.string.background_load_failed, Toast.LENGTH_SHORT).show()
            return
        }
        backgroundView.setImageDrawable(drawable)
        backgroundView.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        player.pause()
        if (multicastLock.isHeld) multicastLock.release()
        stallHandler.removeCallbacks(stallChecker)
    }

    // check app upgrade
    fun checkAppUpdate() {
        Log.d("VVS_TV_LOG", "Checking for app update via custom implementation...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Replace this URL with the actual URL where you host the version.json file
                val url = "https://raw.githubusercontent.com/elich111/elich111.github.io/refs/heads/main/version.json"
                
                // Using a generic try-catch to avoid crashing if URL is invalid or network is down
                val response = java.net.URL(url).readText()
                val jsonObject = JSONObject(response)
                
                val latestVersionCode = jsonObject.optInt("latest_version", -1)
                val downloadUrl = jsonObject.optString("download_url")

                val currentVersionCode = BuildConfig.VERSION_CODE

                
                Log.d("VVS_TV_LOG", "App " +
                        " Check: Current=$currentVersionCode, Latest=$latestVersionCode")

                if (latestVersionCode > currentVersionCode) {
                    withContext(Dispatchers.Main) {
                        showForceUpdateDialog(latestVersionCode, currentVersionCode, downloadUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("VVS_TV_LOG", "Failed to check for custom app update", e)
            }
        }
    }

    private fun showForceUpdateDialog(latestVer: Int, currentVer: Int, downloadUrl: String) {
        // Stop playback if playing
        if (this::player.isInitialized) {
            player.pause()
        }

        // get package info
        val packageInfo = packageManager.getPackageInfo(packageName, 0)



        AlertDialog.Builder(this)
            .setTitle("Update Required")
            .setMessage("A new version of the app is available. Please update to continue using the application: " + latestVer + " current: " + currentVer)
            .setCancelable(false) // Force the user to click the button
            .setPositiveButton("Update Now") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW)
                    //intent.data = Uri.parse("market://details?id=tech.vvs.vvs_launcher")
                    //intent.data = Uri.parse("market://details?id=" + packageInfo.packageName)
                    intent.data = Uri.parse(downloadUrl)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("VVS_TV_LOG", "Failed to download version", e)
                }
                // Close the app so they can't bypass it, or you can let the dialog stay active
                finish()
            }
            .setNegativeButton("Exit App") { _, _ ->
                finish()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()

        enterFullscreen()
        checkAppUpdate()

        if (consumePendingHomeReset()) {
            returnToWelcomeScreen()
            return
        }

        val channel = viewModel.selectedChannel.value
        channel?.let {
            val isUdp = it.uri.startsWith("udp", ignoreCase = true)
            if (isUdp && !multicastLock.isHeld) multicastLock.acquire()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
            lastPosCheckMs = android.os.SystemClock.elapsedRealtime()
            lastPos = player.currentPosition
            stallHandler.removeCallbacks(stallChecker)
            stallHandler.postDelayed(stallChecker, 2_000)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeOpenAboutIntent(intent)) {
            openAboutDialogFromLauncher()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        unregisterReceiver(showWelcomeReceiver)
        unregisterReceiver(openAboutReceiver)
        stallHandler.removeCallbacks(stallChecker)
        player.release()
        // DO NOT Release YouTubePlayerView manually if using lifecycle observer.
        // youTubePlayerView.release()
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private fun rememberLastChannel(channel: Channel) {
        prefs.edit().apply {
            putString("last_uri", channel.uri)
            putString("last_name", channel.name)
        }.apply()
    }



    private fun performAdbResetNetflix() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Determine the path for the adb key in the app's internal storage
                // This avoids the permission issue with the default .android directory
                val keyFile = File(filesDir, "adbkey")
                val pubKeyFile = File(filesDir, "adbkey.pub")
                
                val keyPair = if (keyFile.exists() && pubKeyFile.exists()) {
                    // Read existing key pair
                    AdbKeyPair.read(keyFile, pubKeyFile)
                } else {
                    // Generate new key pair and save to internal storage directly
                    AdbKeyPair.generate(privateKeyFile = keyFile, publicKeyFile = pubKeyFile)
                    AdbKeyPair.read(keyFile, pubKeyFile)
                }

                // Using 'dadb' to connect to local ADB daemon with the custom key pair
                Log.d("VVS_TV_LOG", "Connecting to ADB 127.0.0.1:5555...")
                val adb = Dadb.create("127.0.0.1", 5555, keyPair)
                
                // Diagnostic: Who are we?
                val idRes = adb.shell("id")
                Log.d("VVS_TV_LOG", "ADB Identity: ${idRes.output}")

                Log.d("VVS_TV_LOG", "Attempting Netflix Clear...")
                
                var success = false
                var message = ""

                val response = adb.shell("cmd package clear com.netflix.ninja")
                Log.d("VVS_TV_LOG", "Clear Result: Code=${response.exitCode} Out='${response.output}'")
                
                if (response.exitCode == 0 || response.output.contains("Success", ignoreCase = true)) {
                    success = true
                    message = "Netflix reset successfully"
                } else {
                    // Check for Permission Denial in output OR toString() (since output defaults to empty on some failures)
                    val rawString = response.toString()
                    val isSecurityException = response.output.contains("SecurityException") || 
                                            response.output.contains("permission") ||
                                            rawString.contains("SecurityException") ||
                                            rawString.contains("permission")
                    
                    if (isSecurityException) {
                        Log.d("VVS_TV_LOG", "Permission Denied for Clear. Falling back to Force Stop.")
                        
                        // Fallback: Force Stop
                        adb.shell("am force-stop com.netflix.ninja")
                        
                        success = true 
                        message = "Netflix Restricted: Performed Force Restart instead."
                    } else {
                        message = "Failed: ${response.output}"
                    }
                }

                withContext(Dispatchers.Main) {
                     Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("VVS_TV_LOG", "ADB Error", e)
                withContext(Dispatchers.Main) {
                    val msg = e.message ?: "Unknown Error"
                    Toast.makeText(this@MainActivity, "ADB Error: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun scheduleNetflixReset(context: Context) {
        val prefs = context.getSharedPreferences("vvs_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("netflix_reset_enabled", true) // Default true
        val hour = prefs.getInt("netflix_reset_hour", 3) // Default 3 AM

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NetflixResetReceiver::class.java)
        // Use FLAG_IMMUTABLE per modern Android requirements
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            1001, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (!enabled) {
            alarmManager.cancel(pendingIntent)
            Log.d("VVS_TV_LOG", "Netflix Auto Reset cancelled")
            return
        }

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If time has passed today, schedule for tomorrow
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Use setExactAndAllowWhileIdle for reliability, or setRepeating
        // For simplicity with Doze, setRepeating is okay if not strict, but setExact is better.
        // We'll use setRepeating for convenience as it persists.
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )
        
        Log.d("VVS_TV_LOG", "Netflix Auto Reset scheduled for: ${calendar.time}")
    }
}
