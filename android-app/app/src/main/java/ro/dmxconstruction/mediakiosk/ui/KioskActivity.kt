package ro.dmxconstruction.mediakiosk.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.dmxconstruction.mediakiosk.cache.MediaCache
import ro.dmxconstruction.mediakiosk.data.ApiFactory
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.MediaItemDto
import ro.dmxconstruction.mediakiosk.data.PinResult
import ro.dmxconstruction.mediakiosk.data.PlaybackQueue
import ro.dmxconstruction.mediakiosk.data.PlaylistRepository
import ro.dmxconstruction.mediakiosk.data.PlaylistStore
import ro.dmxconstruction.mediakiosk.data.PlaylistSwitcher
import ro.dmxconstruction.mediakiosk.data.RetryPolicy
import ro.dmxconstruction.mediakiosk.data.RuntimeStateStore
import ro.dmxconstruction.mediakiosk.data.ScreenOrientation
import ro.dmxconstruction.mediakiosk.data.SyncResult
import ro.dmxconstruction.mediakiosk.databinding.ActivityKioskBinding
import ro.dmxconstruction.mediakiosk.kiosk.KioskMode
import java.io.File

class KioskActivity : AppCompatActivity(), Player.Listener {
    private lateinit var binding: ActivityKioskBinding
    private lateinit var player: ExoPlayer
    private lateinit var repository: PlaylistRepository
    private lateinit var cache: MediaCache
    private lateinit var configStore: ConfigStore
    private val queue = PlaybackQueue()
    private var switcher = PlaylistSwitcher()
    private var imageJob: Job? = null
    private var playing = false
    private var currentItem: MediaItemDto? = null
    private val retries = mutableMapOf<Long, Int>()
    private var consecutiveFailures = 0
    private val hotspotTaps = ArrayDeque<Long>()
    private var playbackGeneration = 0L
    private var wasStopped = false
    @get:androidx.annotation.VisibleForTesting
    var isAdminDialogShowing: Boolean = false
        private set
    @get:androidx.annotation.VisibleForTesting
    val currentMediaId: Long?
        get() = currentItem?.id

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        val config = configStore.load()
        if (config == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        requestedOrientation = when (config.orientation) {
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        binding = ActivityKioskBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KioskMode.applyImmersive(this)

        player = ExoPlayer.Builder(this).build().also {
            it.addListener(this)
            binding.playerView.player = it
        }
        val state = RuntimeStateStore(this)
        repository = PlaylistRepository(PlaylistStore(filesDir), state)
        cache = MediaCache(File(filesDir, "media_cache"), ApiFactory.httpClient(), config.cacheLimitBytes)
        switcher = PlaylistSwitcher(repository.offline())
        binding.adminHotspot.setOnClickListener { registerHiddenTap() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        switcher.current?.takeIf { it.items.isNotEmpty() }?.let {
            lifecycleScope.launch { repository.prepare(it, cache) }
            playCurrent()
        } ?: showUnavailable()
        startNetworkLoops()
        handleLockTaskIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLockTaskIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        KioskMode.applyImmersive(this)
    }

    override fun onStart() {
        super.onStart()
        if (wasStopped) {
            wasStopped = false
            if (playing && currentItem?.type == "video") {
                if (player.playerError != null) playCurrent() else player.play()
            } else if (playing && currentItem?.type == "image") {
                playCurrent()
            }
        }
    }

    override fun onStop() {
        wasStopped = true
        imageJob?.cancel()
        imageJob = null
        player.pause()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) KioskMode.applyImmersive(this)
    }

    private fun startNetworkLoops() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { syncLoop() }
                launch { heartbeatLoop() }
            }
        }
    }

    private suspend fun syncLoop() {
        var failures = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            val config = configStore.load() ?: break
            val result = repository.sync(config, switcher.current?.etag)
            when (result) {
                is SyncResult.Updated -> {
                    failures = 0
                    val hadPlaylist = switcher.current != null
                    if (hadPlaylist) {
                        repository.prepare(result.snapshot, cache)
                        switcher.propose(result.snapshot)
                    } else {
                        switcher.propose(result.snapshot)
                        queue.reset()
                        if (result.snapshot.items.isNotEmpty()) {
                            resetFailures()
                            playCurrent()
                        }
                        lifecycleScope.launch { repository.prepare(result.snapshot, cache) }
                    }
                }
                is SyncResult.NotModified -> {
                    failures = 0
                    if (!playing && switcher.current?.items?.isNotEmpty() == true) {
                        resetFailures()
                        queue.reset()
                        playCurrent()
                    }
                }
                is SyncResult.InvalidKey, is SyncResult.Inactive, is SyncResult.NoPlaylist -> failures = 0
                is SyncResult.Failure -> failures++
            }
            delay(if (failures == 0) 60_000L else RetryPolicy.delayMillis(failures - 1))
        }
    }

    private suspend fun heartbeatLoop() {
        var failures = 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            val config = configStore.load() ?: break
            if (repository.heartbeat(config)) failures = 0 else failures++
            delay(if (failures == 0) 60_000L else RetryPolicy.delayMillis(failures - 1))
        }
    }

    private fun playCurrent() {
        val snapshot = switcher.current
        val item = snapshot?.let { queue.current(it.items) }
        if (item == null) {
            showUnavailable()
            return
        }
        playing = true
        currentItem = item
        val generation = ++playbackGeneration
        imageJob?.cancel()
        binding.unavailableText.visibility = View.GONE
        if (item.type == "image") showImage(item, generation) else showVideo(item)
    }

    private fun showImage(item: MediaItemDto, generation: Long) {
        player.stop()
        binding.playerView.visibility = View.GONE
        binding.imageContainer.visibility = View.VISIBLE
        val source: Any = cache.cached(item) ?: item.url
        Glide.with(this).load(source).fitCenter().listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
            ): Boolean {
                mediaFailed(item, generation)
                return true
            }

            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean
            ): Boolean {
                binding.imageView.setImageDrawable(resource)
                if (!isCurrent(item, generation)) return true
                mediaSucceeded(item)
                imageJob = lifecycleScope.launch {
                    delay((item.durationSeconds ?: 10).toLong() * 1000L)
                    if (isCurrent(item, generation)) itemFinished()
                }
                return true
            }
        }).into(binding.imageView)
    }

    private fun showVideo(item: MediaItemDto) {
        Glide.with(this).clear(binding.imageView)
        binding.imageContainer.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        val local = cache.cached(item)
        val uri = local?.let(Uri::fromFile) ?: Uri.parse(item.url)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) itemFinished()
    }

    override fun onRenderedFirstFrame() {
        currentItem?.let(::mediaSucceeded)
    }

    override fun onPlayerError(error: PlaybackException) {
        mediaFailed()
    }

    private fun mediaSucceeded(item: MediaItemDto) {
        retries.remove(item.id)
        consecutiveFailures = 0
    }

    private fun mediaFailed() {
        val item = currentItem ?: return
        mediaFailed(item, playbackGeneration)
    }

    private fun mediaFailed(item: MediaItemDto, generation: Long) {
        if (!isCurrent(item, generation)) return
        val count = retries[item.id] ?: 0
        if (count < 2) {
            retries[item.id] = count + 1
            lifecycleScope.launch {
                delay(1_000L)
                if (isCurrent(item, generation)) playCurrent()
            }
        } else {
            retries.remove(item.id)
            consecutiveFailures++
            RuntimeStateStore(this).lastError = "Materialul «${item.title.take(80)}» nu a putut fi redat."
            itemFinished()
        }
    }

    private fun itemFinished() {
        imageJob?.cancel()
        val beforeVersion = switcher.current?.playlist?.version
        val snapshot = switcher.onItemBoundary()
        if (snapshot == null || snapshot.items.isEmpty()) {
            showUnavailable()
            return
        }
        if (snapshot.playlist.version != beforeVersion) {
            queue.reset()
            resetFailures()
            val protected = snapshot.items.mapNotNull(cache::nameFor).toSet()
            cache.removeUnused(protected)
        } else queue.advance(snapshot.items)
        if (consecutiveFailures >= snapshot.items.size.coerceAtLeast(1)) showUnavailable() else playCurrent()
    }

    private fun showUnavailable() {
        playing = false
        player.stop()
        binding.playerView.visibility = View.GONE
        binding.imageContainer.visibility = View.GONE
        binding.unavailableText.visibility = View.VISIBLE
    }

    private fun resetFailures() {
        retries.clear()
        consecutiveFailures = 0
    }

    private fun isCurrent(item: MediaItemDto, generation: Long): Boolean =
        currentItem?.id == item.id && playbackGeneration == generation

    private fun handleLockTaskIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_START_LOCK_TASK, false) == true) {
            intent.removeExtra(EXTRA_START_LOCK_TASK)
            KioskMode.startLockTaskIfAuthorized(this)
        }
    }

    private fun registerHiddenTap() {
        val now = System.currentTimeMillis()
        while (hotspotTaps.isNotEmpty() && now - hotspotTaps.first() > 2_000L) hotspotTaps.removeFirst()
        hotspotTaps.addLast(now)
        if (hotspotTaps.size >= 5) {
            hotspotTaps.clear()
            showPinDialog()
        }
    }

    private fun showPinDialog() {
        isAdminDialogShowing = true
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN administrare"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Acces administrativ")
            .setView(input)
            .setNegativeButton("Anulează", null)
            .setPositiveButton("Deschide", null)
            .create()
        dialog.setOnDismissListener { isAdminDialogShowing = false }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                when (val result = configStore.verifyPin(input.text.toString())) {
                    PinResult.Valid -> {
                        dialog.dismiss()
                        startActivity(Intent(this, AdminActivity::class.java))
                    }
                    PinResult.Invalid -> dialog.setMessage("PIN incorect.")
                    is PinResult.Locked -> dialog.setMessage("Prea multe încercări. Reîncearcă în ${result.remainingMs / 1000 + 1} secunde.")
                }
                input.text.clear()
            }
        }
        dialog.show()
    }

    override fun onDestroy() {
        imageJob?.cancel()
        binding.playerView.player = null
        player.removeListener(this)
        player.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_START_LOCK_TASK = "start_lock_task"
    }
}
