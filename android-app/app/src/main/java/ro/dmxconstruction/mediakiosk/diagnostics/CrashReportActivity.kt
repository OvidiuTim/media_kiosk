package ro.dmxconstruction.mediakiosk.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import ro.dmxconstruction.mediakiosk.R
import ro.dmxconstruction.mediakiosk.ui.SetupActivity

class CrashReportActivity : Activity() {
    private lateinit var store: CrashReportStore
    private var diagnosticActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CrashReportStore(this)
        store.setDiagnosticActive(true)
        diagnosticActive = true
        val report = store.read() ?: "Raportul de eroare nu a putut fi citit."
        setContentView(buildContent(report))
    }

    override fun onStart() {
        super.onStart()
        store.setDiagnosticActive(true)
        diagnosticActive = true
    }

    override fun onStop() {
        if (diagnosticActive) store.setDiagnosticActive(false)
        super.onStop()
    }

    private fun buildContent(report: String): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(18, 24, 32))
        }
        content.addView(TextView(this).apply {
            id = R.id.crashTitle
            text = "Aplicația s-a închis neașteptat"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "Copiază sau partajează raportul de mai jos. Acesta nu conține cheia dispozitivului."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
        content.addView(TextView(this).apply {
            id = R.id.crashReportText
            text = report
            textSize = 12f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(32, 42, 54))
        }, matchWrap())
        content.addView(safeButton("Copiază eroarea").apply {
            id = R.id.copyCrashButton
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Media Kiosk crash", report))
                text = "Eroare copiată"
            }
        }, buttonLayout(dp(12)))
        content.addView(safeButton("Partajează raportul").apply {
            id = R.id.shareCrashButton
            setOnClickListener { shareReport(report) }
        }, buttonLayout(dp(8)))
        content.addView(safeButton("Încearcă din nou").apply {
            id = R.id.continueCrashButton
            setOnClickListener {
                diagnosticActive = false
                store.clear()
                startActivity(
                    Intent(this@CrashReportActivity, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
        }, buttonLayout(dp(8)))
        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(18, 24, 32))
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun shareReport(report: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Raport crash Media Kiosk")
            putExtra(Intent.EXTRA_TEXT, report)
            runCatching {
                val uri: Uri = FileProvider.getUriForFile(
                    this@CrashReportActivity,
                    "${packageName}.files",
                    store.reportFile
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "Raport crash Media Kiosk", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { startActivity(Intent.createChooser(send, "Partajează raportul")) }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun safeButton(label: String) = TextView(this).apply {
        text = label
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.button_background)
    }

    private fun buttonLayout(topMargin: Int) = matchWrap().apply { this.topMargin = topMargin }
}
