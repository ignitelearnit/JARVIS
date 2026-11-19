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
import android.media.AudioManager

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import java.util.Locale
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.widget.Toast
import androidx.core.content.FileProvider
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
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
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    CrashLog.add("JS ERROR", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                }
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                CrashLog.add("WEBVIEW RENDERER DIED", if (detail.didCrash()) "crashed" else "killed by Android (memory)")
                // keep the app alive: rebuild the page instead of crashing
                main.post {
                    pageReady = false
                    try { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() } catch (_: Exception) {}
                    recreate()
                }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                val pass = JSONObject.quote(prefs.getString("pass", "") ?: "")
                val hq = prefs.getBoolean("hq", true)
                web.evaluateJavascript("window.jarvisStartup && window.jarvisStartup($pass, $hq)", null)
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
        val hq = CheckBox(this).apply {
            text = "Real Jarvis voice (heavier, may lag on older phones)"
            isChecked = prefs.getBoolean("hq", true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(url); addView(pass); addView(hq)
        }
        AlertDialog.Builder(this)
            .setTitle("Jarvis settings")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString("url", url.text.toString().trim().ifBlank { DEFAULT_URL })
                    .putString("pass", pass.text.toString().trim())
                    .putBoolean("hq", hq.isChecked)
                    .apply()
                pageReady = false
                web.loadUrl(siteUrl)
                WakeService.start(this)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Share log") { _, _ -> shareLog() }
            .show()
    }

    private fun shareLog() {
        val f = CrashLog.file()
        if (f == null || !f.exists()) { Toast.makeText(this, "No log yet", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", f)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Share Jarvis log"))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) CrashLog.add("LOW MEMORY WARNING", "trim level $level")
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

    // ---- speaking (Android's own voice, no heavy model) ----------------------

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var queued: String? = null

    private fun spoke() {
        main.post { web.evaluateJavascript("window.jarvisSpoke && window.jarvisSpoke()", null) }
    }

    private fun boostVolume() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val floor = (max * 0.75).toInt()
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < floor) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, floor, 0)
        }
    }

    private fun doSpeak(text: String) {
        val t = tts ?: return spoke()
        boostVolume()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        if (t.speak(text, TextToSpeech.QUEUE_FLUSH, params, "jarvis") == TextToSpeech.ERROR) spoke()
    }

    private fun speakNow(text: String) {
        if (tts == null) {
            queued = text
            tts = TextToSpeech(this) { status ->
                if (status != TextToSpeech.SUCCESS) { tts = null; spoke(); return@TextToSpeech }
                ttsReady = true
                tts?.apply {
                    language = Locale.UK
                    setPitch(0.85f)
                    setSpeechRate(0.98f)
                    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { spoke() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { spoke() }
                    })
                }
                queued?.let { q -> queued = null; doSpeak(q) }
            }
        } else if (!ttsReady) {
            queued = text
        } else doSpeak(text)
    }

    inner class Bridge {
        @JavascriptInterface
        fun listen() { main.post { startRecognition() } }

        @JavascriptInterface
        fun speak(text: String) { main.post { speakNow(text) } }

        @JavascriptInterface
        fun stopSpeaking() { main.post { tts?.stop() } }
    }

    override fun onDestroy() {
        stopRecognition()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val DEFAULT_URL = "https://vkhjarvis.netlify.app"
        private const val REQ_PERMS = 1
    }
}
