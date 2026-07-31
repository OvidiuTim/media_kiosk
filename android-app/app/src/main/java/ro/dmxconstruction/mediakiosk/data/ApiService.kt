package ro.dmxconstruction.mediakiosk.data

import okhttp3.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface KioskApi {
    @GET("api/kiosk/playlist/")
    suspend fun playlist(
        @Header("X-Device-Key") deviceKey: String,
        @Header("If-None-Match") etag: String? = null
    ): Response<PlaylistResponse>

    @POST("api/kiosk/heartbeat/")
    suspend fun heartbeat(@Header("X-Device-Key") deviceKey: String): Response<HeartbeatResponse>
}

object ApiFactory {
    fun create(serverUrl: String, calls: Call.Factory): KioskApi = Retrofit.Builder()
        .baseUrl("${ServerUrl.normalize(serverUrl)}/")
        .callFactory(calls)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(KioskApi::class.java)
}
