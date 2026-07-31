package ro.dmxconstruction.mediakiosk.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ro.dmxconstruction.mediakiosk.data.AppConfig
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.DeviceKeyValidator
import ro.dmxconstruction.mediakiosk.data.PlaylistRepository
import ro.dmxconstruction.mediakiosk.data.PlaylistStore
import ro.dmxconstruction.mediakiosk.data.RuntimeStateStore
import ro.dmxconstruction.mediakiosk.data.ScreenOrientation
import ro.dmxconstruction.mediakiosk.data.ServerUrl
import ro.dmxconstruction.mediakiosk.data.SecureNetwork
import ro.dmxconstruction.mediakiosk.data.SyncResult
import ro.dmxconstruction.mediakiosk.databinding.ActivitySetupBinding
import ro.dmxconstruction.mediakiosk.diagnostics.CrashReportNavigator

class SetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySetupBinding
    private lateinit var configStore: ConfigStore
    private var validatedSignature: String? = null
    private var validatedSnapshot: ro.dmxconstruction.mediakiosk.data.PlaylistSnapshot? = null
    private val isEditing by lazy { intent.getBooleanExtra(EXTRA_EDIT, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashReportNavigator.redirectIfNeeded(this)) return
        configStore = ConfigStore(this)
        if (configStore.isConfigured() && !isEditing) {
            openKiosk()
            return
        }
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        populate()
        binding.serverInput.doAfterTextChanged { invalidateTest() }
        binding.deviceKeyInput.doAfterTextChanged { invalidateTest() }
        binding.testButton.setOnClickListener { testConnection() }
        binding.saveButton.setOnClickListener { saveAndStart() }
    }

    private fun populate() {
        val existing = configStore.load()
        binding.serverInput.setText(existing?.serverUrl ?: ConfigStore.DEFAULT_SERVER)
        binding.deviceKeyInput.setText(existing?.deviceKey.orEmpty())
        binding.cacheSpinner.setSelection(cacheOptions.indexOf(existing?.cacheLimitBytes ?: ConfigStore.DEFAULT_CACHE).coerceAtLeast(0))
        binding.orientationSpinner.setSelection(
            when (existing?.orientation ?: ScreenOrientation.LANDSCAPE) {
                ScreenOrientation.LANDSCAPE -> 0
                ScreenOrientation.PORTRAIT -> 1
                ScreenOrientation.AUTO -> 2
            }
        )
        if (existing != null) {
            validatedSignature = signature(existing)
            binding.saveButton.isEnabled = true
            binding.pinInput.hint = "Lasă gol pentru a păstra PIN-ul"
            binding.pinLabel.text = "PIN administrare (opțional la modificare)"
        }
    }

    private fun invalidateTest() {
        validatedSignature = null
        validatedSnapshot = null
        binding.saveButton.isEnabled = false
    }

    private fun testConnection() {
        val config = readConfig() ?: return
        setBusy(true)
        lifecycleScope.launch {
            val repository = PlaylistRepository(
                PlaylistStore(filesDir),
                RuntimeStateStore(this@SetupActivity),
                calls = SecureNetwork.callFactory(this@SetupActivity)
            )
            when (val result = repository.sync(config, null, persist = false)) {
                is SyncResult.Updated -> {
                    validatedSignature = signature(config)
                    validatedSnapshot = result.snapshot
                    binding.statusText.text = "Conexiune reușită\nDispozitiv: ${result.snapshot.device.name}\nPlaylist: ${result.snapshot.playlist.name} (v${result.snapshot.playlist.version})\nMateriale valide: ${result.snapshot.items.size}"
                    binding.saveButton.isEnabled = true
                }
                is SyncResult.NoPlaylist -> {
                    validatedSignature = signature(config)
                    validatedSnapshot = null
                    binding.statusText.text = "Cheia este validă, dar dispozitivul nu are încă un playlist publicat."
                    binding.saveButton.isEnabled = true
                }
                is SyncResult.InvalidKey -> binding.statusText.text = result.message
                is SyncResult.Inactive -> binding.statusText.text = result.message
                is SyncResult.Failure -> binding.statusText.text = result.message
                is SyncResult.NotModified -> binding.statusText.text = "Conexiune reușită. Playlistul nu s-a modificat."
            }
            binding.statusText.visibility = View.VISIBLE
            setBusy(false)
        }
    }

    private fun readConfig(): AppConfig? {
        val server = runCatching { ServerUrl.normalize(binding.serverInput.text.toString()) }.getOrElse {
            showError(it.message ?: "Adresa serverului nu este validă.")
            return null
        }
        val key = binding.deviceKeyInput.text.toString().trim()
        if (!DeviceKeyValidator.isValid(key)) {
            showError("Cheia dispozitivului trebuie să fie un UUID valid.")
            return null
        }
        return AppConfig(
            server,
            key,
            cacheOptions[binding.cacheSpinner.selectedItemPosition],
            when (binding.orientationSpinner.selectedItemPosition) {
                1 -> ScreenOrientation.PORTRAIT
                2 -> ScreenOrientation.AUTO
                else -> ScreenOrientation.LANDSCAPE
            }
        )
    }

    private fun saveAndStart() {
        val config = readConfig() ?: return
        if (validatedSignature != signature(config)) {
            showError("Testează din nou conexiunea înainte de salvare.")
            return
        }
        val pin = binding.pinInput.text.toString()
        if (!configStore.isConfigured() && (pin.length < 4 || !pin.all(Char::isDigit))) {
            showError("Alege un PIN administrativ de minimum 4 cifre.")
            return
        }
        runCatching {
            val previousSignature = configStore.load()?.let(::signature)
            configStore.save(config, pin.ifBlank { null })
            val store = PlaylistStore(filesDir)
            validatedSnapshot?.let(store::save)
                ?: if (previousSignature != signature(config)) store.clear() else Unit
        }
            .onSuccess { openKiosk() }
            .onFailure { showError(it.message ?: "Setările nu au putut fi salvate.") }
    }

    private fun signature(config: AppConfig) = "${config.serverUrl}|${config.deviceKey}"

    private fun showError(message: String) {
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        binding.testButton.isEnabled = !busy
        binding.testButton.text = if (busy) "Se verifică..." else "Testează conexiunea"
    }

    private fun openKiosk() {
        startActivity(
            Intent(this, KioskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_EDIT = "edit_settings"
        private val cacheOptions = longArrayOf(256L, 512L, 1024L, 2048L).map { it * 1024 * 1024 }
    }
}
