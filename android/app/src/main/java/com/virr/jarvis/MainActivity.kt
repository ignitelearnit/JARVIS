package com.virr.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * Hosts the Jarvis web page and does the listening (Android speech recognition).
 * The web page does the thinking (Claude) and the talking (Jarvis voice).
 * WakeService wakes this up when it hears "Jarvis".
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var pageReady = false
    private var pendingWake = false
    private var askedOverlay = false

    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private val siteUrl get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                val pass = JSONObject.quote(prefs.getString("pass", "") ?: "")
                web.evaluateJavascript("window.jarvisStartup && window.jarvisStartup($pass)", null)
                if (pendingWake) { pendingWake = false; wake() }
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidJarvis")
        web.setOnLongClickListener { showSettings(); true }

        if (intent?.getBooleanExtra(WakeService.EXTRA_WAKE, false) == true) pendingWake = true
        setup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(WakeService.EXTRA_WAKE, false)) {
            if (pageReady) wake() else pendingWake = true
        }
    }

    // ---- first-run set-up -------------------------------------------------

    private fun setup() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true
            AlertDialog.Builder(this)
                .setTitle("One more permission")
                .setMessage("Jarvis needs 'Display over other apps' so he can pop up when you say \"Hey Jarvis\". Tap OK, find Jarvis in the list and switch it on, then come back.")
                .setPositiveButton("OK") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Skip", null)
                .show()
        }
        if (!prefs.contains("url")) {
            showSettings()
            return
        }
        launch()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        setup()
    }

    private fun launch() {
        if (web.url == null) web.loadUrl(siteUrl)
        WakeService.start(this)
    }

    private fun showSettings() {
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val url = field("Jarvis site address", prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL)
        val pass = field("Site passcode", prefs.getString("pass", "") ?: "", true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(url); addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle("Jarvis settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("url", url.text.toString().trim().ifBlank { DEFAULT_URL })
                    .putString("pass", pass.text.toString().trim())
                    .apply()
                pageReady = false
                web.loadUrl(siteUrl)
                WakeService.start(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- conversation ------------------------------------------------------

    private fun wake() {
        web.evaluateJavascript("window.jarvisWake && window.jarvisWake()", null)
    }

    private fun startRecognition() {
        stopRecognition()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { endSession(); return }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text.isNullOrBlank()) {
                        endSession()
                    } else {
                        web.evaluateJavascript("window.jarvisHeard && window.jarvisHeard(${JSONObject.quote(text)})", null)
                    }
                }
                override fun onError(error: Int) { endSession() }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        recognizer?.startListening(i)
    }

    private fun stopRecognition() {
        recognizer?.let {
            try { it.cancel(); it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
    }

    /** Conversation finished (silence or error): hand the mic back to the wake word and step aside. */
    private fun endSession() {
        stopRecognition()
        web.evaluateJavascript("window.jarvisIdle && window.jarvisIdle()", null)
        WakeService.start(this, WakeService.ACTION_RESUME)
        main.postDelayed({ moveTaskToBack(true) }, 300)
    }

    inner class Bridge {
        @JavascriptInterface
        fun listen() { main.post { startRecognition() } }
    }

    override fun onDestroy() {
        stopRecognition()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_URL = "https://virr-jarvis.netlify.app"
        private const val REQ_PERMS = 1
    }
}
