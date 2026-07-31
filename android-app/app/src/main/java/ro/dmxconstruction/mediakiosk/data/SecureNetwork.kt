package ro.dmxconstruction.mediakiosk.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import ro.dmxconstruction.mediakiosk.R
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object SecureNetwork {
    const val ISRG_FALLBACK_HOST = "kiosk.dmxconstruction.ro"
    const val ISRG_ROOT_X1_SHA256 =
        "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6"

    @Volatile private var shared: HostRoutingCallFactory? = null

    fun callFactory(context: Context): HostRoutingCallFactory {
        shared?.let { return it }
        return synchronized(this) {
            shared ?: build(context.applicationContext, Build.VERSION.SDK_INT).also { shared = it }
        }
    }

    internal fun build(context: Context, sdkInt: Int): HostRoutingCallFactory {
        val systemTrust = systemTrustManager()
        val isrgTrust = isrgTrustManager(context)
        val compositeTrust = SystemThenIsrgTrustManager(systemTrust, isrgTrust)

        val systemClient = baseBuilder(sdkInt).build()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(compositeTrust), null)
        }
        val fallbackClient = baseBuilder(sdkInt)
            .sslSocketFactory(sslContext.socketFactory, compositeTrust)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps || url.host != ISRG_FALLBACK_HOST) {
                    throw SSLPeerUnverifiedException(
                        "Fallbackul ISRG Root X1 este permis numai pentru https://$ISRG_FALLBACK_HOST"
                    )
                }
                chain.proceed(chain.request())
            }
            .build()
        return HostRoutingCallFactory(systemClient, fallbackClient, ISRG_FALLBACK_HOST)
    }

    internal fun isrgCertificate(context: Context): X509Certificate {
        val certificate = context.resources.openRawResource(R.raw.isrg_root_x1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }
        check(fingerprint == ISRG_ROOT_X1_SHA256) {
            "Fingerprint ISRG Root X1 invalid: $fingerprint"
        }
        check(certificate.basicConstraints >= 0 && certificate.subjectX500Principal == certificate.issuerX500Principal) {
            "Resursa ISRG Root X1 nu este un certificat CA rădăcină autosemnat."
        }
        return certificate
    }

    internal fun isrgTrustManager(context: Context): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("isrg-root-x1", isrgCertificate(context))
        }
        return trustManager(store)
    }

    private fun systemTrustManager(): X509TrustManager = trustManager(null)

    private fun trustManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private fun baseBuilder(sdkInt: Int): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .writeTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                if (!chain.request().url.isHttps) {
                    throw SSLPeerUnverifiedException("Media Kiosk permite numai conexiuni HTTPS.")
                }
                chain.proceed(chain.request())
            }
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "MediaKiosk-Android/1.0")
                        .build()
                )
            }
        if (sdkInt <= Build.VERSION_CODES.LOLLIPOP_MR1) {
            builder.connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build()
                )
            )
        }
        return builder
    }
}

class HostRoutingCallFactory internal constructor(
    internal val systemClient: OkHttpClient,
    internal val isrgFallbackClient: OkHttpClient,
    private val fallbackHost: String
) : Call.Factory {
    override fun newCall(request: Request): Call {
        require(request.url.isHttps) { "Media Kiosk permite numai conexiuni HTTPS." }
        return clientForHost(request.url.host).newCall(request)
    }

    internal fun clientForHost(host: String): OkHttpClient =
        if (host.equals(fallbackHost, ignoreCase = true)) isrgFallbackClient else systemClient
}

@SuppressLint("CustomX509TrustManager") // Compune două implementări PKIX reale; nu acceptă direct niciun certificat.
internal class SystemThenIsrgTrustManager internal constructor(
    private val system: X509TrustManager,
    private val isrg: X509TrustManager
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        system.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (systemFailure: CertificateException) {
            try {
                isrg.checkServerTrusted(chain, authType)
            } catch (isrgFailure: CertificateException) {
                isrgFailure.addSuppressed(systemFailure)
                throw isrgFailure
            }
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + isrg.acceptedIssuers
}
