package ro.dmxconstruction.mediakiosk.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ro.dmxconstruction.mediakiosk.R
import ro.dmxconstruction.mediakiosk.ui.SetupActivity

@RunWith(AndroidJUnit4::class)
class CrashReportActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store: CrashReportStore get() = CrashReportStore(context)

    @Before fun reset() {
        store.clear()
        context.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun clean() {
        store.clear()
    }

    @Test fun raportul_apare_inainte_de_configurare_la_urmatoarea_pornire() {
        store.save(Thread.currentThread(), IllegalStateException("crash tabletă rk3288"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(CrashReportActivity::class.java.name, null, false)
        ActivityScenario.launch(SetupActivity::class.java).use {
            val reportActivity = monitor.waitForActivityWithTimeout(5_000)
            assertNotNull(reportActivity)
            assertEquals(
                "Aplicația s-a închis neașteptat",
                reportActivity?.findViewById<TextView>(R.id.crashTitle)?.text?.toString()
            )
            reportActivity?.finish()
        }
        instrumentation.removeMonitor(monitor)
    }

    @Test fun raportul_poate_fi_copiat_si_partajat() {
        store.save(Thread.currentThread(), IllegalStateException("eroare completă pentru suport"))
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            )
            ActivityScenario.launch(CrashReportActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<TextView>(R.id.crashReportText).text.contains("eroare completă"))
                    activity.findViewById<TextView>(R.id.copyCrashButton).performClick()
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.contains("eroare completă") == true)
                    activity.findViewById<TextView>(R.id.shareCrashButton).performClick()
                }
            }
            intended(hasAction(Intent.ACTION_CHOOSER))
        } finally {
            Intents.release()
        }
    }

    @Test fun incearca_din_nou_sterge_raportul_vechi() {
        store.save(Thread.currentThread(), IllegalStateException("raport deja afișat"))
        ActivityScenario.launch(CrashReportActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val retry = activity.findViewById<TextView>(R.id.continueCrashButton)
                assertEquals("Încearcă din nou", retry.text.toString())
                retry.performClick()
                assertTrue(!store.hasReport())
            }
        }
    }
}
