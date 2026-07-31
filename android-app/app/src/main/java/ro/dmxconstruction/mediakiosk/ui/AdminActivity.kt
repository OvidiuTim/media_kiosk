package ro.dmxconstruction.mediakiosk.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ro.dmxconstruction.mediakiosk.BuildConfig
import ro.dmxconstruction.mediakiosk.cache.MediaCache
import ro.dmxconstruction.mediakiosk.data.ApiFactory
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.PinResult
import ro.dmxconstruction.mediakiosk.data.PlaylistRepository
import ro.dmxconstruction.mediakiosk.data.PlaylistStore
import ro.dmxconstruction.mediakiosk.data.RuntimeStateStore
import ro.dmxconstruction.mediakiosk.data.SyncResult
import ro.dmxconstruction.mediakiosk.databinding.ActivityAdminBinding
import ro.dmxconstruction.mediakiosk.diagnostics.CrashReportNavigator
import ro.dmxconstruction.mediakiosk.kiosk.KioskMode
import ro.dmxconstruction.mediakiosk.kiosk.HomeLauncherController
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class AdminActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminBinding
    private lateinit var configStore: ConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashReportNavigator.redirectIfNeeded(this)) return
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = ConfigStore(this)
        binding.autostartSwitch.isChecked = configStore.autostartEnabled
        binding.autostartSwitch.setOnCheckedChangeListener { _, enabled ->
            configStore.autostartEnabled = enabled
            refresh(if (enabled) "Pornirea automată este activă." else "Pornirea automată este dezactivată.")
        }
        refresh()
        binding.syncButton.setOnClickListener { syncNow() }
        binding.cleanButton.setOnClickListener { cleanUnused() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_EDIT, true))
        }
        binding.exitKioskButton.setOnClickListener {
            KioskMode.exit(this)
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(home)
        }
        binding.lockTaskButton.isEnabled = KioskMode.isLockTaskPermitted(this)
        binding.lockTaskButton.setOnClickListener {
            startActivity(
                Intent(this, KioskActivity::class.java)
                    .putExtra(KioskActivity.EXTRA_START_LOCK_TASK, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
        binding.setHomeButton.setOnClickListener { showHomeSelectionExplanation() }
        binding.systemLauncherButton.setOnClickListener { showSystemLauncherPinDialog() }
        binding.backButton.setOnClickListener { finish() }
    }

    private fun cache() = configStore.load()?.let {
        MediaCache(File(filesDir, "media_cache"), ApiFactory.httpClient(), it.cacheLimitBytes)
    }

    private fun refresh(status: String? = null) {
        val config = configStore.load() ?: return
        val snapshot = PlaylistStore(filesDir).load()
        val state = RuntimeStateStore(this)
        val mediaCache = cache()
        val keySuffix = config.deviceKey.takeLast(4).padStart(4, '•')
        binding.infoText.text = buildString {
            status?.let { append(it).append("\n\n") }
            append("Dispozitiv: ${snapshot?.device?.name ?: "Necunoscut"}\n")
            append("Server: ${config.serverUrl}\n")
            append("Cheie: ••••$keySuffix\n")
            append("Playlist: ${snapshot?.playlist?.name ?: "Niciun playlist"}\n")
            append("Versiune playlist: ${snapshot?.playlist?.version ?: "—"}\n")
            append("Versiune aplicație: ${BuildConfig.VERSION_NAME}\n")
            append("Ultima sincronizare: ${formatTime(state.lastSync)}\n")
            append("Ultimul heartbeat: ${formatTime(state.lastHeartbeat)}\n")
            append("Internet: ${if (isOnline()) "conectat" else "offline"}\n")
            append("Pornire automată: ${if (configStore.autostartEnabled) "activă" else "dezactivată"}\n")
            append("Aplicație principală: ${if (HomeLauncherController.isDefaultHome(this@AdminActivity)) "Media Kiosk" else "launcherul sistemului"}\n")
            append("Lock Task: ${if (KioskMode.isLockTaskActive(this@AdminActivity)) "activ" else if (KioskMode.isLockTaskPermitted(this@AdminActivity)) "autorizat, inactiv" else "neautorizat"}\n")
            append("Cache utilizat: ${formatBytes(mediaCache?.usedBytes() ?: 0)}\n")
            append("Limită cache: ${formatBytes(config.cacheLimitBytes)}\n")
            append("Materiale locale: ${mediaCache?.localCount() ?: 0}\n")
            append("Ultima pornire: ${state.lastBootStatus ?: "Nicio informație"}\n")
            append("Ultima eroare: ${state.lastError ?: "Nicio eroare"}")
        }
    }

    private fun showHomeSelectionExplanation() {
        SafeActionDialog.create(
            context = this,
            title = "Media Kiosk ca aplicație principală",
            message = "În selectorul Android alege Media Kiosk, apoi opțiunea Întotdeauna. Aplicația va fi deschisă automat după pornirea tabletei.",
            positiveLabel = "Deschide selectorul"
        ) { handle ->
                handle.dialog.dismiss()
                HomeLauncherController.enableHomeCandidate(this)
                openSettings(HomeLauncherController.createHomeSelectionIntent(this))
        }.show()
    }

    private fun showSystemLauncherPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN administrare"
        }
        val dialog = SafeActionDialog.create(
            context = this,
            title = "Revino la launcherul sistemului",
            message = "Confirmă PIN-ul administrativ pentru a dezactiva Media Kiosk ca aplicație Home.",
            customView = input,
            positiveLabel = "Continuă"
        ) { handle ->
            when (val result = HomeLauncherController.disableAfterPin(this, input.text.toString())) {
                PinResult.Valid -> {
                    KioskMode.exit(this)
                    handle.dialog.dismiss()
                    openSettings(HomeLauncherController.createSystemHomeSettingsIntent())
                }
                PinResult.Invalid -> handle.setMessage("PIN incorect. Media Kiosk rămâne aplicația principală.")
                is PinResult.Locked -> handle.setMessage(
                    "Prea multe încercări. Reîncearcă în ${result.remainingMs / 1000 + 1} secunde."
                )
            }
            input.text.clear()
        }
        dialog.show()
    }

    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun syncNow() {
        val config = configStore.load() ?: return
        binding.syncButton.isEnabled = false
        lifecycleScope.launch {
            val repository = PlaylistRepository(PlaylistStore(filesDir), RuntimeStateStore(this@AdminActivity))
            val message = when (val result = repository.sync(config)) {
                is SyncResult.Updated -> "Playlist actualizat la versiunea ${result.snapshot.playlist.version}."
                is SyncResult.NotModified -> "Playlistul este deja actualizat."
                is SyncResult.InvalidKey -> result.message
                is SyncResult.Inactive -> result.message
                is SyncResult.NoPlaylist -> result.message
                is SyncResult.Failure -> result.message
            }
            binding.syncButton.isEnabled = true
            refresh(message)
        }
    }

    private fun cleanUnused() {
        val snapshot = PlaylistStore(filesDir).load()
        val mediaCache = cache() ?: return
        val protected = snapshot?.items?.mapNotNull(mediaCache::nameFor)?.toSet().orEmpty()
        val removed = mediaCache.removeUnused(protected)
        refresh("Au fost eliminate $removed fișiere neutilizate.")
    }

    private fun isOnline(): Boolean {
        @Suppress("DEPRECATION")
        return (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
    }

    private fun formatTime(value: Long): String = if (value == 0L) "Niciodată"
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale("ro", "RO")).format(Date(value))

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    }
}
