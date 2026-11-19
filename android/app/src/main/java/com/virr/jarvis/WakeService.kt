package com.virr.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.homeassistant.companion.android.microwakeword.MicroWakeWord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for "Hey Jarvis" in the background using the open-source microWakeWord detector.
 * When it hears it, it releases the microphone and opens the app, which does the talking.
 * The app tells it to RESUME when the conversation is over.
 */
class WakeService : Service() {

    private class Listener(val active: AtomicBoolean = AtomicBoolean(true)) {
        var thread: Thread? = null
    }

    private var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLog.add("SERVICE", "onStartCommand action=${intent?.action}")
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Listening for \"Hey Jarvis\""),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
            }
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        stopListening()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("Microphone permission needed")
            return
        }
        val l = Listener()
        listener = l
        l.thread = Thread { listenLoop(l) }.also { it.name = "jarvis-wake"; it.start() }
        update("Listening for \"Hey Jarvis\"")
    }

    private fun stopListening() {
        val l = listener ?: return
        l.active.set(false)
        try { l.thread?.join(1500) } catch (_: InterruptedException) {}
        listener = null
    }

    private fun listenLoop(l: Listener) {
        var detector: MicroWakeWord? = null
        var record: AudioRecord? = null
        var detected = false
        try {
            detector = createDetector()
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2)
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone is busy" }
            record.startRecording()

            val chunk = ShortArray(CHUNK_SAMPLES)
            while (l.active.get() && !detected) {
                var read = 0
                while (read < chunk.size && l.active.get()) {
                    val n = record.read(chunk, read, chunk.size - read)
                    check(n >= 0) { "Microphone read error $n" }
                    read += n
                }
                if (read == chunk.size && detector.processAudio(chunk)) {
                    detector.reset()
                    detected = true
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Wake word listener failed", e)
            CrashLog.add("WAKE LISTENER FAILED", e)
            main.post { update("Wake word error: ${e.message}") }
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { record?.release() } catch (_: Exception) {}
            try { detector?.close() } catch (_: Exception) {}
        }
        if (detected && l.active.get()) main.post { onWake() }
    }

    /** Loads the Hey Jarvis model and feeds it a few seconds of quiet noise so it is ready at once. */
    private fun createDetector(): MicroWakeWord {
        val bytes = assets.open("wakeword/hey_jarvis.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes)
        model.rewind()
        // Values from hey_jarvis.json (Kevin Ahrendt's microWakeWord model)
        val d = MicroWakeWord(
            modelBuffer = model,
            featureStepSizeMs = 10,
            probabilityCutoff = 0.97f,
            slidingWindowSize = 5,
        )
        val noise = Random(42L)
        val chunk = ShortArray(CHUNK_SAMPLES)
        repeat(WARM_UP_CHUNKS) {
            for (i in chunk.indices) chunk[i] = (noise.nextGaussian() * 25.0).toInt().toShort()
            d.processAudio(chunk)
        }
        return d
    }

    private fun onWake() {
        CrashLog.add("SERVICE", "wake word detected, opening app")
        listener = null
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_WAKE, true)
        try {
            startActivity(open)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the app (is 'Display over other apps' allowed?)", e)
            CrashLog.add("COULD NOT OPEN APP", e)
            update("Allow 'Display over other apps' for Jarvis")
            startListening()
        }
    }

    override fun onDestroy() {
        CrashLog.add("SERVICE", "onDestroy")
        stopListening()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jarvis wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 160          // 10 ms at 16 kHz
        private const val WARM_UP_CHUNKS = 400         // 4 s of quiet noise, processed faster than real time
        const val EXTRA_WAKE = "wake"
        const val ACTION_STOP = "com.virr.jarvis.STOP"
        const val ACTION_RESUME = "com.virr.jarvis.RESUME"

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, WakeService::class.java)
            if (action != null) i.action = action
            ContextCompat.startForegroundService(context, i)
        }
    }
}
