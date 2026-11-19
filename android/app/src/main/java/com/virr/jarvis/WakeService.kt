package com.virr.jarvis

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Listens for the wake word "Jarvis" in the background.
 * When it hears it, it pauses itself and opens the app, which does the talking.
 * The app tells it to RESUME when the conversation is over.
 */
class WakeService : Service() {

    private var porcupine: PorcupineManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Listening for \"Jarvis\""),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
            }
            ACTION_RELEASE -> releaseEngine()      // key changed, rebuild next time
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        val key = getSharedPreferences("jarvis", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) {
            update("Add your Picovoice key in Jarvis settings")
            return
        }
        try {
            if (porcupine == null) {
                porcupine = PorcupineManager.Builder()
                    .setAccessKey(key)
                    .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                    .setSensitivity(0.65f)
                    .build(applicationContext, PorcupineManagerCallback { onWake() })
            }
            porcupine?.start()
            update("Listening for \"Jarvis\"")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Wake word error", e)
            update("Wake word error: ${e.message}")
            releaseEngine()
        }
    }

    private fun onWake() {
        try { porcupine?.stop() } catch (e: Exception) { Log.w(TAG, e) }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_WAKE, true)
        try {
            startActivity(open)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the app (is 'Display over other apps' allowed?)", e)
            update("Allow 'Display over other apps' for Jarvis")
            startListening()
        }
    }

    private fun stopListening() {
        try { porcupine?.stop() } catch (e: Exception) { Log.w(TAG, e) }
    }

    private fun releaseEngine() {
        try { porcupine?.stop() } catch (e: Exception) { Log.w(TAG, e) }
        try { porcupine?.delete() } catch (e: Exception) { Log.w(TAG, e) }
        porcupine = null
    }

    override fun onDestroy() {
        releaseEngine()
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
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
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
        const val EXTRA_WAKE = "wake"
        const val ACTION_STOP = "com.virr.jarvis.STOP"
        const val ACTION_RELEASE = "com.virr.jarvis.RELEASE"
        const val ACTION_RESUME = "com.virr.jarvis.RESUME"

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, WakeService::class.java)
            if (action != null) i.action = action
            ContextCompat.startForegroundService(context, i)
        }
    }
}
