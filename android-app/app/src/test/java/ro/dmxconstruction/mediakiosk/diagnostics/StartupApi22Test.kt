package ro.dmxconstruction.mediakiosk.diagnostics

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ro.dmxconstruction.mediakiosk.MediaKioskApplication
import ro.dmxconstruction.mediakiosk.R
import ro.dmxconstruction.mediakiosk.data.AppConfig
import ro.dmxconstruction.mediakiosk.data.ConfigStore
import ro.dmxconstruction.mediakiosk.data.ScreenOrientation
import ro.dmxconstruction.mediakiosk.ui.AdminActivity
import ro.dmxconstruction.mediakiosk.ui.KioskActivity
import ro.dmxconstruction.mediakiosk.ui.SafeActionDialog
import ro.dmxconstruction.mediakiosk.ui.SetupActivity
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [22])
class StartupApi22Test {
    private val application: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: CrashReportStore

    @Before fun reset() {
        store = CrashReportStore(application)
        store.clear()
        application.getSharedPreferences("private_kiosk_config", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences("kiosk_runtime_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun clean() {
        store.clear()
    }

    @Test fun `Application instaleaza handlerul in startup pe API 22`() {
        assertTrue(application is MediaKioskApplication)
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() is CrashExceptionHandler)
    }

    @Test fun `tema si ecranul de configurare se incarca pe API 22`() {
        Robolectric.buildActivity(SetupActivity::class.java).setup().use { controller ->
            assertNotNull(controller.get().findViewById<TextView>(R.id.serverInput))
            val testButton = controller.get().findViewById<TextView>(R.id.testButton)
            val saveButton = controller.get().findViewById<TextView>(R.id.saveButton)
            assertFalse(testButton is Button)
            assertFalse(saveButton is Button)
            assertTrue(testButton.isClickable)
            assertTrue(saveButton.isFocusable)
        }
    }

    @Test fun `layouturile nu mai contin Button sau atribute text moderne pe actiuni`() {
        assertSafeActions(R.layout.activity_setup, setOf(R.id.testButton, R.id.saveButton))
        assertSafeActions(
            R.layout.activity_admin,
            setOf(
                R.id.syncButton, R.id.cleanButton, R.id.settingsButton, R.id.lockTaskButton,
                R.id.setHomeButton, R.id.systemLauncherButton, R.id.exitKioskButton, R.id.backButton
            )
        )
    }

    @Test fun `toate actiunile din administrare se infleaza fara Button pe API 22`() {
        configure()
        Robolectric.buildActivity(AdminActivity::class.java).setup().use { controller ->
            listOf(
                R.id.syncButton, R.id.cleanButton, R.id.settingsButton, R.id.lockTaskButton,
                R.id.setHomeButton, R.id.systemLauncherButton, R.id.exitKioskButton, R.id.backButton
            ).forEach { id ->
                val action = controller.get().findViewById<TextView>(id)
                assertNotNull(action)
                assertFalse(action is Button)
                assertTrue(action.isClickable)
            }
        }
    }

    @Test fun `dialogurile aplicatiei nu construiesc Button pe API 22`() {
        Robolectric.buildActivity(SetupActivity::class.java).setup().use { controller ->
            val dialog = SafeActionDialog.create(
                context = controller.get(),
                title = "Dialog sigur",
                message = "Test API 22",
                positiveLabel = "Continuă"
            ) { it.dialog.dismiss() }
            dialog.show()
            assertFalse(containsButton(dialog.window?.decorView))
            dialog.dismiss()
        }
    }

    @Test fun `raportul este afisat inaintea fluxului normal si poate fi copiat sau partajat`() {
        store.save(Thread.currentThread(), IllegalStateException("eroare API 22"))
        Robolectric.buildActivity(SetupActivity::class.java).create().use { setup ->
            val redirect = shadowOf(setup.get()).nextStartedActivity
            assertEquals(CrashReportActivity::class.java.name, redirect.component?.className)
        }

        Robolectric.buildActivity(CrashReportActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertEquals(
                "Aplicația s-a închis neașteptat",
                activity.findViewById<TextView>(R.id.crashTitle).text.toString()
            )
            assertTrue(activity.findViewById<TextView>(R.id.crashReportText).text.contains("eroare API 22"))

            activity.findViewById<TextView>(R.id.copyCrashButton).performClick()
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.contains("eroare API 22") == true)

            activity.findViewById<TextView>(R.id.shareCrashButton).performClick()
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertEquals(Intent.ACTION_SEND, send?.action)
            assertTrue(send?.getStringExtra(Intent.EXTRA_TEXT)?.contains("eroare API 22") == true)
        }
    }

    @Test fun `markerul de crash al paginii de diagnostic evita bucla o pornire`() {
        store.save(Thread.currentThread(), IllegalStateException("raport pastrat"))
        store.setDiagnosticActive(true)
        val first = Robolectric.buildActivity(SetupActivity::class.java).create()
        assertFalse(first.get().isFinishing)
        assertFalse(store.isDiagnosticActive())
        first.destroy()

        val second = Robolectric.buildActivity(SetupActivity::class.java).create()
        assertTrue(second.get().isFinishing)
        assertEquals(
            CrashReportActivity::class.java.name,
            shadowOf(second.get()).nextStartedActivity.component?.className
        )
        second.destroy()
    }

    @Test fun `Incearca din nou sterge raportul vechi si redeschide fluxul normal`() {
        store.save(Thread.currentThread(), IllegalStateException("raport vechi"))
        Robolectric.buildActivity(CrashReportActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertEquals("Încearcă din nou", activity.findViewById<TextView>(R.id.continueCrashButton).text)
            activity.findViewById<TextView>(R.id.continueCrashButton).performClick()

            assertFalse(store.hasReport())
            assertEquals(
                SetupActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className
            )
        }
    }

    @Test fun `Media3 si playerul kiosk pornesc cu configuratie pe API 22`() {
        configure()
        Robolectric.buildActivity(KioskActivity::class.java).create().start().resume().visible().use { controller ->
            assertNotNull(controller.get().findViewById<android.view.View>(R.id.playerView))
        }
    }

    private fun configure() {
        ConfigStore(application).save(
            AppConfig(
                "https://127.0.0.1:1",
                UUID.randomUUID().toString(),
                ConfigStore.DEFAULT_CACHE,
                ScreenOrientation.LANDSCAPE
            ),
            "1234"
        )
    }

    private fun assertSafeActions(layout: Int, expectedIds: Set<Int>) {
        val parser = application.resources.getLayout(layout)
        val found = mutableSetOf<Int>()
        val forbidden = setOf("fontFamily", "textAppearance", "textAllCaps", "style")
        parser.use {
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    val id = parser.getAttributeResourceValue(
                        "http://schemas.android.com/apk/res/android", "id", 0
                    )
                    if (id in expectedIds) {
                        found += id
                        assertEquals("TextView", parser.name)
                        repeat(parser.attributeCount) { index ->
                            assertFalse(parser.getAttributeName(index) in forbidden)
                        }
                    }
                    assertFalse(parser.name == "Button")
                }
                event = parser.next()
            }
        }
        assertEquals(expectedIds, found)
    }

    private fun containsButton(view: android.view.View?): Boolean {
        if (view == null) return false
        if (view is Button) return true
        if (view !is android.view.ViewGroup) return false
        return (0 until view.childCount).any { containsButton(view.getChildAt(it)) }
    }
}
