package ro.dmxconstruction.mediakiosk.data

import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

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
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "MediaKiosk-Android/1.0")
                .build()
            chain.proceed(request)
        }
        .build()

    fun create(serverUrl: String, client: OkHttpClient = httpClient()): KioskApi = Retrofit.Builder()
        .baseUrl("${ServerUrl.normalize(serverUrl)}/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(KioskApi::class.java)
}
