package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps FCC mode active by re-applying the FCC
 * profile every 2 seconds. This runs independently of the Activity lifecycle
 * so it continues working when the user switches to DJI Fly.
 */
class FccKeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID = "fcc_keepalive"
        const val NOTIFICATION_ID = 9012
        const val ACTION_START = "com.freefcc.app.START_KEEPALIVE"
        const val ACTION_STOP = "com.freefcc.app.STOP_KEEPALIVE"

        fun start(context: Context) {
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepaliveJob: Job? = null
    private val transport = DumlTransport()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                keepaliveJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startKeepaliveLoop()
            }
            else -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startKeepaliveLoop()
            }
        }
        return START_STICKY
    }

    private fun startKeepaliveLoop() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                if (HardwareLock.tryBegin()) {
                    try {
                        val profile = Profiles.load(this@FccKeepaliveService, "fcc_keepalive.json")
                        transport.sendFrames(
                            frames = profile.frames,
                            rounds = 1,
                            interFrameDelayMs = profile.interFrameDelay,
                            readWindowMs = profile.readWindowMs,
                            port = profile.port
                        )
                    } catch (_: Exception) {
                    } finally {
                        HardwareLock.end()
                    }
                }
                // else: a manual operation (or another tick) holds the lock — skip this tick, never queue/block.
                delay(2000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FCC Keepalive",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps FCC mode active in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("FreeFCC")
            .setContentText("Maintaining FCC mode...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        keepaliveJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}