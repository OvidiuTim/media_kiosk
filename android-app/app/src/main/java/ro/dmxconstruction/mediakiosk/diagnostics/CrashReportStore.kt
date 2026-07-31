package ro.dmxconstruction.mediakiosk.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CrashReportStore(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY)
    val reportFile: File = File(directory, REPORT_FILE)
    private val diagnosticActiveFile = File(directory, DIAGNOSTIC_ACTIVE_FILE)

    fun save(thread: Thread, throwable: Throwable) {
        directory.mkdirs()
        val temporary = File(directory, "$REPORT_FILE.part")
        val report = buildReport(thread, throwable)
        FileOutputStream(temporary, false).use { output ->
            output.write(report.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        replaceReport(temporary)
    }

    fun read(): String? = runCatching {
        reportFile.takeIf(File::isFile)?.reader(Charsets.UTF_8)?.use { it.readText() }
    }.getOrNull()

    fun hasReport(): Boolean = reportFile.isFile && reportFile.length() > 0

    fun clear() {
        reportFile.delete()
        File(directory, "$REPORT_FILE.part").delete()
        setDiagnosticActive(false)
    }

    fun setDiagnosticActive(active: Boolean) {
        runCatching {
            directory.mkdirs()
            if (active) diagnosticActiveFile.writeText("active", Charsets.UTF_8)
            else diagnosticActiveFile.delete()
        }
    }

    fun isDiagnosticActive(): Boolean = diagnosticActiveFile.isFile

    /**
     * Dacă ecranul de diagnostic a provocat el însuși terminarea procesului, nu îl
     * redeschidem automat încă o dată. Raportul original rămâne pe disc.
     */
    fun consumeDiagnosticFailureBypass(): Boolean {
        if (!isDiagnosticActive()) return false
        diagnosticActiveFile.delete()
        return true
    }

    private fun replaceReport(temporary: File) {
        try {
            // android.system.Os este disponibil începând cu API 21.
            android.system.Os.rename(temporary.absolutePath, reportFile.absolutePath)
            if (reportFile.isFile && !temporary.exists()) return
        } catch (_: Throwable) {
            // Continuăm cu fallbackul Java de mai jos.
        }
        reportFile.delete()
        if (!temporary.renameTo(reportFile)) {
            FileOutputStream(reportFile, false).use { output ->
                temporary.inputStream().use { it.copyTo(output) }
                output.fd.sync()
            }
            temporary.delete()
        }
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        return buildString {
            append("Media Kiosk - raport crash\n")
            append("Data: ").append(timestamp).append('\n')
            append("Thread: ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
            append("Android SDK: ").append(Build.VERSION.SDK_INT).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE ?: "necunoscut").append('\n')
            append("Producător: ").append(Build.MANUFACTURER ?: "necunoscut").append('\n')
            append("Model: ").append(Build.MODEL ?: "necunoscut").append('\n')
            append("Dispozitiv: ").append(Build.DEVICE ?: "necunoscut").append('\n')
            append("Hardware: ").append(Build.HARDWARE ?: "necunoscut").append('\n')
            append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString().ifBlank { "necunoscut" }).append('\n')
            append("\nStack trace complet:\n")
            append(stack)
        }
    }

    companion object {
        private const val DIRECTORY = "crash_reports"
        private const val REPORT_FILE = "last_crash.txt"
        private const val DIAGNOSTIC_ACTIVE_FILE = "diagnostic_active"
    }
}

class CrashExceptionHandler(
    context: Context,
    private val delegate: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    private val store = CrashReportStore(context.applicationContext ?: context)
    private val handling = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (handling.compareAndSet(false, true) && !store.isDiagnosticActive()) {
            runCatching { store.save(thread, throwable) }
        }
        if (delegate != null && delegate !== this) {
            delegate.uncaughtException(thread, throwable)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}
