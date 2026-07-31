package ro.dmxconstruction.mediakiosk.diagnostics

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
class CrashReportStoreTest {
    private val application: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: CrashReportStore

    @Before fun reset() {
        store = CrashReportStore(application)
        store.clear()
    }

    @After fun clean() {
        store.clear()
    }

    @Test fun `salveaza sincron stack trace-ul complet inainte de handlerul sistemului`() {
        var delegateCalled = false
        val delegate = Thread.UncaughtExceptionHandler { _, _ ->
            assertTrue(store.hasReport())
            delegateCalled = true
        }
        val cause = IllegalArgumentException("codec rk3288")
        CrashExceptionHandler(application, delegate).uncaughtException(
            Thread.currentThread(),
            IllegalStateException("player startup", cause)
        )

        val report = store.read().orEmpty()
        assertTrue(delegateCalled)
        assertTrue(report.contains("java.lang.IllegalStateException: player startup"))
        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: codec rk3288"))
        assertTrue(report.contains("CrashReportStoreTest"))
        assertTrue(report.contains("Android SDK: 22"))
        assertFalse(store.reportFile.resolveSibling("last_crash.txt.part").exists())
    }

    @Test fun `handlerul nu se recaptureaza si nu suprascrie raportul din pagina de diagnostic`() {
        store.save(Thread.currentThread(), IllegalStateException("raport original"))
        store.setDiagnosticActive(true)
        var calls = 0
        val handler = CrashExceptionHandler(application) { _, _ -> calls++ }

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("crash diagnostic"))
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("al doilea crash"))

        assertEquals(2, calls)
        assertTrue(store.read().orEmpty().contains("raport original"))
        assertFalse(store.read().orEmpty().contains("crash diagnostic"))
    }
}
