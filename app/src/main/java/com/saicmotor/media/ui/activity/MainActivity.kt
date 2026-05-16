package com.saicmotor.media.ui.activity

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import coil.load
import com.saicmotor.media.MyApplication
import com.saicmotor.media.R
import com.saicmotor.media.data.SubsonicSettings
import com.saicmotor.media.databinding.ActivityMainBinding
import com.saicmotor.media.databinding.DialogSubsonicSettingsBinding
import com.saicmotor.media.service.MediaService
import com.saicmotor.media.subsonic.SubsonicClient
import com.saicmotor.media.ui.fragment.BrowseFragment
import com.saicmotor.media.ui.fragment.NowPlayingFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaBrowser: MediaBrowserCompat

    private var skinReceiverRegistered = false

    private val skinReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val skinConfig = Settings.System.getInt(contentResolver, "SKIN_THEME_CONFIG", 1)
            val newMode = MyApplication.nightModeFromSkinConfig(skinConfig)
            if (AppCompatDelegate.getDefaultNightMode() != newMode) {
                // Updating the global setting triggers AppCompat's applyDayNight().
                // Because uiMode is declared in our manifest configChanges, the
                // result is an onConfigurationChanged() callback rather than a
                // destroy/recreate — so the MediaBrowser connection, current
                // playback state, and back-stack all survive the theme flip.
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val token = mediaBrowser.sessionToken
            val controller = MediaControllerCompat(this@MainActivity, token)
            MediaControllerCompat.setMediaController(this@MainActivity, controller)
            controller.registerCallback(controllerCallback)
            controllerCallback.onMetadataChanged(controller.metadata)
            controllerCallback.onPlaybackStateChanged(controller.playbackState)
            nowPlayingFragment()?.onControllerReady()
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            val title  = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)  ?: ""
            val artist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
            binding.miniTitle.text  = title
            binding.miniArtist.text = artist
            val miniVis = if (title.isNotEmpty()) View.VISIBLE else View.GONE
            // Only show if Now Playing isn't already fullscreen
            if (supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) == null) {
                binding.miniPlayer.visibility = miniVis
                binding.miniPlayerDivider.visibility = miniVis
            }

            val artUriStr = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
            binding.miniAlbumArt.load(artUriStr?.let { Uri.parse(it) }) {
                placeholder(R.drawable.ic_album_art_placeholder)
                error(R.drawable.ic_album_art_placeholder)
            }
        }
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            val playing = state?.state == PlaybackStateCompat.STATE_PLAYING
            binding.miniBtnPlayPause.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start MediaService explicitly so it survives the activity going to the background.
        // Without this the service only runs while a client is bound; once onStop() disconnects
        // the MediaBrowserCompat the service is destroyed and playback stops.
        startForegroundService(Intent(this, MediaService::class.java))

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MediaService::class.java),
            connectionCallback,
            null
        )

        setupClickListeners()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.content_container, BrowseFragment(), TAG_BROWSE)
                .commit()
            highlightSource(MediaService.USB1_ROOT)
        }

        // Hide the full-screen overlay as soon as Now Playing is popped from
        // the back stack — covers both the hardware back button and the in-app
        // close button (which both call popBackStack()).
        supportFragmentManager.addOnBackStackChangedListener {
            val nowPlayingVisible =
                supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) != null
            binding.fullscreenContainer.visibility =
                if (nowPlayingVisible) View.VISIBLE else View.GONE
            if (!nowPlayingVisible) showMiniPlayer()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!skinReceiverRegistered) {
            registerReceiver(skinReceiver, IntentFilter("com.saicmotor.changeSkin"))
            skinReceiverRegistered = true
        }
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        if (skinReceiverRegistered) {
            unregisterReceiver(skinReceiver)
            skinReceiverRegistered = false
        }
        MediaControllerCompat.getMediaController(this)?.unregisterCallback(controllerCallback)
        mediaBrowser.disconnect()
    }

    override fun onBackPressed() {
        val browse = browseFragment()
        if (supportFragmentManager.backStackEntryCount > 0) {
            // The OnBackStackChangedListener handles hiding the overlay and
            // restoring the mini player once the pop completes.
            supportFragmentManager.popBackStack()
        } else if (browse?.navigateBack() == true) {
            // handled
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Delivered by the system when uiMode (or any other declared configChanges
     * value) changes while the activity is alive.  We declare uiMode in the
     * manifest specifically to receive this in place of a destroy/recreate
     * when SAIC's headlight skin broadcast flips between day and night.
     *
     * Re-inflating the layout is necessary because most colour references in
     * the app are direct `@color/…` lookups; existing views don't re-resolve
     * their colours when the configuration changes.  By detaching all
     * fragments around the re-inflation we let FragmentManager rebuild their
     * view trees against the new theme too — internal fragment state (scroll
     * position, current category, etc.) survives because the fragments
     * themselves stay in the manager.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeChange()
    }

    private fun applyThemeChange() {
        val activeSource      = browseFragment()?.sourceRoot ?: MediaService.USB1_ROOT
        val nowPlayingVisible = supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) != null

        // Detach every fragment so its view is torn down before we swap out
        // the host layout.  Fragments stay in FragmentManager; only their
        // views are destroyed.
        val fragments = supportFragmentManager.fragments
        if (fragments.isNotEmpty()) {
            supportFragmentManager.beginTransaction().apply {
                fragments.forEach { detach(it) }
            }.commitNowAllowingStateLoss()
        }

        // Re-inflate the activity layout against the new theme.  The
        // OnBackStackChangedListener registered in onCreate still works —
        // it captures `binding` as a property reference, so it sees the
        // freshly-assigned value here.
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()

        // Re-attach the fragments — their views are rebuilt inside the
        // new containers and pick up the new theme automatically.
        if (fragments.isNotEmpty()) {
            supportFragmentManager.beginTransaction().apply {
                fragments.forEach { attach(it) }
            }.commitNowAllowingStateLoss()
        }

        // Restore UI state that lives outside the fragments.
        highlightSource(activeSource)
        binding.fullscreenContainer.visibility =
            if (nowPlayingVisible) View.VISIBLE else View.GONE

        // Re-push the current playback state to the freshly-inflated mini player.
        // The MediaController itself is still attached to the activity — no
        // reconnection to the service is needed.
        MediaControllerCompat.getMediaController(this)?.let { controller ->
            controllerCallback.onMetadataChanged(controller.metadata)
            controllerCallback.onPlaybackStateChanged(controller.playbackState)
        }
    }

    private fun setupClickListeners() {
        // Source tab clicks
        binding.btnSourceUsb1.setOnClickListener   { switchSource(MediaService.USB1_ROOT) }
        binding.btnSourceOnline.setOnClickListener { switchSource(MediaService.ONLINE_ROOT) }
        binding.btnSourceBt.setOnClickListener     { switchSource(MediaService.BT_ROOT) }

        // Long-press on Subsonic tab → re-open settings
        binding.btnSourceOnline.setOnLongClickListener {
            showSubsonicSettings()
            true
        }

        // Now Playing pill
        binding.btnNowPlaying.setOnClickListener { showNowPlaying() }

        // Mini player controls
        binding.miniBtnPlayPause.setOnClickListener {
            val c = MediaControllerCompat.getMediaController(this)
            if (c?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING)
                c.transportControls.pause() else c?.transportControls?.play()
        }
        binding.miniBtnPrevious.setOnClickListener {
            MediaControllerCompat.getMediaController(this)?.transportControls?.skipToPrevious()
        }
        binding.miniBtnNext.setOnClickListener {
            MediaControllerCompat.getMediaController(this)?.transportControls?.skipToNext()
        }
        binding.miniPlayer.setOnClickListener { showNowPlaying() }
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun switchSource(root: String) {
        highlightSource(root)
        // Tell MediaService which source is now in the foreground so it can
        // route transport controls correctly (ExoPlayer vs AVRCP).
        startService(
            Intent(this, MediaService::class.java)
                .setAction(MediaService.ACTION_SET_SOURCE)
                .putExtra(MediaService.EXTRA_SOURCE, root)
        )
        if (root == MediaService.BT_ROOT) {
            // Bluetooth has no browse tree — jump straight to the Now Playing screen
            showNowPlaying()
            return
        }
        browseFragment()?.sourceRoot = root
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            showMiniPlayer()
        }
        // Auto-prompt settings if Subsonic has never been configured
        if (root == MediaService.ONLINE_ROOT && !SubsonicSettings.load(this).isValid) {
            showSubsonicSettings()
        }
    }

    private fun showMiniPlayer() {
        val hasTrack = binding.miniTitle.text.isNotEmpty()
        val vis = if (hasTrack) View.VISIBLE else View.GONE
        binding.miniPlayer.visibility = vis
        binding.miniPlayerDivider.visibility = vis
    }

    internal fun showNowPlaying() {
        if (supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) != null) return
        binding.miniPlayer.visibility = View.GONE
        binding.miniPlayerDivider.visibility = View.GONE
        binding.fullscreenContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fullscreen_container, NowPlayingFragment(), TAG_NOW_PLAYING)
            .addToBackStack(null)
            .commit()
        binding.root.post {
            nowPlayingFragment()?.onControllerReady()
        }
    }

    /**
     * Shows the Subsonic server configuration dialog.
     * Pre-fills current settings; saves on OK and triggers a re-browse
     * if the Subsonic source is currently active.
     */
    internal fun showSubsonicSettings() {
        val cfg         = SubsonicSettings.load(this)
        val dialogBinding = DialogSubsonicSettingsBinding.inflate(layoutInflater)

        dialogBinding.editUrl.setText(cfg.url)
        dialogBinding.editUsername.setText(cfg.username)
        dialogBinding.editPassword.setText(cfg.password)

        AlertDialog.Builder(this)
            .setTitle(R.string.subsonic_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newCfg = SubsonicSettings.Config(
                    url      = dialogBinding.editUrl.text.toString().trim().trimEnd('/'),
                    username = dialogBinding.editUsername.text.toString().trim(),
                    password = dialogBinding.editPassword.text.toString()
                )
                SubsonicSettings.save(this, newCfg)
                SubsonicClient.invalidateCache()
                // Re-browse if the Subsonic source is already selected
                browseFragment()?.let { fragment ->
                    if (fragment.sourceRoot == MediaService.ONLINE_ROOT) {
                        fragment.sourceRoot = MediaService.ONLINE_ROOT
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun highlightSource(root: String) {
        val accent    = getColor(R.color.accent)
        val secondary = getColor(R.color.text_secondary)
        binding.btnSourceUsb1.setTextColor(   if (root == MediaService.USB1_ROOT)   accent else secondary)
        binding.btnSourceOnline.setTextColor( if (root == MediaService.ONLINE_ROOT) accent else secondary)
        binding.btnSourceBt.setTextColor(     if (root == MediaService.BT_ROOT)     accent else secondary)
    }

    private fun browseFragment() =
        supportFragmentManager.findFragmentByTag(TAG_BROWSE) as? BrowseFragment

    private fun nowPlayingFragment() =
        supportFragmentManager.findFragmentByTag(TAG_NOW_PLAYING) as? NowPlayingFragment

    companion object {
        private const val TAG_BROWSE      = "browse"
        private const val TAG_NOW_PLAYING = "now_playing"
    }
}
