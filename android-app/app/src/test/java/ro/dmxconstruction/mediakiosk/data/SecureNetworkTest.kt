package ro.dmxconstruction.mediakiosk.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
class SecureNetworkTest {
    private val application: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `certificatul inclus este ISRG Root X1 oficial`() {
        val certificate = SecureNetwork.isrgCertificate(application)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }

        assertEquals(SecureNetwork.ISRG_ROOT_X1_SHA256, fingerprint)
        assertEquals("CN=ISRG Root X1,O=Internet Security Research Group,C=US", certificate.subjectX500Principal.name)
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.basicConstraints >= 0)
    }

    @Test fun `trust managerul incearca sistemul inainte de fallback`() {
        val system = RecordingTrustManager()
        val fallback = RecordingTrustManager(failure = CertificateException("nu trebuie apelat"))
        SystemThenIsrgTrustManager(system, fallback).checkServerTrusted(emptyArray(), "RSA")

        assertEquals(1, system.serverChecks)
        assertEquals(0, fallback.serverChecks)
    }

    @Test fun `fallbackul este incercat numai dupa esecul validarii standard`() {
        val systemFailure = CertificateException("CA lipsă din sistem")
        val system = RecordingTrustManager(failure = systemFailure)
        val fallback = RecordingTrustManager()
        SystemThenIsrgTrustManager(system, fallback).checkServerTrusted(emptyArray(), "RSA")

        assertEquals(1, system.serverChecks)
        assertEquals(1, fallback.serverChecks)
    }

    @Test fun `trust managerul ISRG respinge un certificat autosemnat necunoscut`() {
        val untrusted = HeldCertificate.Builder().commonName("untrusted.example").build().certificate
        assertThrows(CertificateException::class.java) {
            SecureNetwork.isrgTrustManager(application).checkServerTrusted(arrayOf(untrusted), "RSA")
        }
    }

    @Test fun `fallbackul este rutat numai pentru hostname-ul exact`() {
        val network = SecureNetwork.build(application, 22)
        assertSame(
            network.isrgFallbackClient,
            network.clientForHost("kiosk.dmxconstruction.ro")
        )
        assertSame(network.systemClient, network.clientForHost("www.kiosk.dmxconstruction.ro"))
        assertSame(network.systemClient, network.clientForHost("kiosk.dmxconstruction.ro.example"))
        assertNotSame(network.systemClient, network.isrgFallbackClient)
    }

    @Test fun `clientii pastreaza HostnameVerifier-ul standard OkHttp`() {
        val network = SecureNetwork.build(application, 22)
        val standardVerifier = OkHttpClient().hostnameVerifier

        assertSame(standardVerifier, network.systemClient.hostnameVerifier)
        assertSame(standardVerifier, network.isrgFallbackClient.hostnameVerifier)
    }

    @Test fun `Glide foloseste loaderul OkHttp configurat de aplicatie`() {
        val loaders = Glide.get(application).registry.getModelLoaders(
            GlideUrl("https://kiosk.dmxconstruction.ro/media/test.jpg")
        )
        assertTrue(loaders.any { it.javaClass.name.contains("OkHttpUrlLoader") })
    }

    @Test fun `API 22 permite exclusiv TLS 1_2 si respinge HTTP`() {
        val network = SecureNetwork.build(application, 22)
        listOf(network.systemClient, network.isrgFallbackClient).forEach { client ->
            assertEquals(
                setOf(TlsVersion.TLS_1_2),
                client.connectionSpecs.flatMap { it.tlsVersions.orEmpty() }.toSet()
            )
            assertFalse(client.connectionSpecs.any { !it.isTls })
        }
        assertThrows(IllegalArgumentException::class.java) {
            network.newCall(Request.Builder().url("http://kiosk.dmxconstruction.ro/").build())
        }
    }
}

private class RecordingTrustManager(
    private val failure: CertificateException? = null
) : X509TrustManager {
    var serverChecks = 0

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        serverChecks++
        failure?.let { throw it }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
