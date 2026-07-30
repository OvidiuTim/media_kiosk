package ro.dmxconstruction.mediakiosk.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ro.dmxconstruction.mediakiosk.cache.MediaCache
import com.google.gson.Gson

class PlaylistRepository(
    private val store: PlaylistStore,
    private val state: RuntimeStateStore? = null,
    private val apiProvider: (String) -> KioskApi = { ApiFactory.create(it) }
) {
    private val requestMutex = Mutex()

    suspend fun sync(
        config: AppConfig,
        etag: String? = store.load()?.etag,
        persist: Boolean = true
    ): SyncResult = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val response = apiProvider(config.serverUrl).playlist(config.deviceKey, etag)
                when (response.code()) {
                    200 -> {
                        val body = response.body() ?: return@withContext SyncResult.Failure("Serverul a trimis un răspuns gol.")
                        val snapshot = PlaylistValidator.validate(body, config.serverUrl, response.headers()["ETag"])
                        if (persist) store.save(snapshot)
                        state?.lastSync = System.currentTimeMillis()
                        state?.lastError = null
                        SyncResult.Updated(snapshot)
                    }
                    304 -> {
                        state?.lastSync = System.currentTimeMillis()
                        SyncResult.NotModified(store.load())
                    }
                    401 -> SyncResult.InvalidKey("Cheia dispozitivului este invalidă.")
                    403 -> SyncResult.Inactive("Dispozitivul este inactiv.")
                    404 -> when (apiError(response)) {
                        "Dispozitivul nu are un playlist asociat.",
                        "Playlistul nu are o versiune publicată activă." ->
                            SyncResult.NoPlaylist("Dispozitivul nu are un playlist publicat activ.")
                        else -> SyncResult.Failure("Endpointul playlist nu a fost găsit pe serverul configurat.")
                    }
                    else -> SyncResult.Failure("Serverul a răspuns cu codul ${response.code()}.")
                }
            } catch (error: Exception) {
                val message = safeError(error)
                state?.lastError = message
                SyncResult.Failure(message)
            }
        }
    }

    suspend fun heartbeat(config: AppConfig): Boolean = requestMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val response = apiProvider(config.serverUrl).heartbeat(config.deviceKey)
                if (response.isSuccessful && response.body()?.success == true) {
                    state?.lastHeartbeat = System.currentTimeMillis()
                    true
                } else false
            }.getOrElse {
                state?.lastError = safeError(it)
                false
            }
        }
    }

    suspend fun prepare(snapshot: PlaylistSnapshot, cache: MediaCache) {
        val protected = snapshot.items.mapNotNull(cache::nameFor).toSet()
        snapshot.items.forEach { item ->
            try {
                cache.obtain(item, protected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Elementul rămâne disponibil pentru streaming; celelalte descărcări continuă.
            }
        }
        cache.trim(protected)
    }

    fun offline(): PlaylistSnapshot? = store.load()

    private fun apiError(response: retrofit2.Response<*>): String? = runCatching {
        response.errorBody()?.charStream()?.use { Gson().fromJson(it, PlaylistResponse::class.java)?.error }
    }.getOrNull()

    private fun safeError(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "Server indisponibil. Se folosește conținutul local."
        is java.net.SocketTimeoutException -> "Conexiunea cu serverul a expirat."
        is javax.net.ssl.SSLException -> "Conexiunea HTTPS nu a putut fi validată."
        is IllegalArgumentException -> error.message ?: "Date API invalide."
        else -> "Sincronizarea nu a reușit."
    }
}

sealed class SyncResult {
    data class Updated(val snapshot: PlaylistSnapshot) : SyncResult()
    data class NotModified(val snapshot: PlaylistSnapshot?) : SyncResult()
    data class InvalidKey(val message: String) : SyncResult()
    data class Inactive(val message: String) : SyncResult()
    data class NoPlaylist(val message: String) : SyncResult()
    data class Failure(val message: String) : SyncResult()
}
