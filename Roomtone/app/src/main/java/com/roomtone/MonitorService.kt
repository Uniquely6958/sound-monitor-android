package com.roomtone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.roomtone.analysis.RollingPowerWindow
import com.roomtone.analysis.pcm16MeanSquare
import com.roomtone.analysis.powerToDbfs
import kotlin.math.max

enum class AlertMode(val label: String) {
    TONE("Tone"), VIBRATION("Vibration"), BOTH("Both"),
}

data class MonitorSettings(
    val thresholdDbfs: Int = -25,
    val windowSeconds: Int = 5,
    val alertMode: AlertMode = AlertMode.BOTH,
)

data class MonitorSnapshot(
    val active: Boolean = false,
    val ready: Boolean = false,
    val alerting: Boolean = false,
    val currentDbfs: Double = -120.0,
    val averageDbfs: Double? = null,
    val thresholdDbfs: Int = -25,
    val windowSeconds: Int = 5,
    val elapsedMillis: Long = 0L,
    val error: String? = null,
)

class MonitorService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): MonitorService = this@MonitorService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Roomtone:Monitor")
    }

    @Volatile private var settings = MonitorSettings()
    @Volatile private var running = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var snapshot = MonitorSnapshot()
    private var observer: ((MonitorSnapshot) -> Unit)? = null
    private var worker: Thread? = null
    private var startedAt = 0L
    private var lastNotificationAt = 0L
    private var toneGenerator: ToneGenerator? = null
    private var toneRepeater: Runnable? = null
    private var vibrator: Vibrator? = null
    private var activeAlertMode: AlertMode? = null
    private var vibrationActive = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    @Volatile private var alertWarning: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setObserver(listener: ((MonitorSnapshot) -> Unit)?) {
        observer = listener
        listener?.invoke(snapshot)
    }

    fun currentSnapshot(): MonitorSnapshot = snapshot

    fun configure(newSettings: MonitorSettings) {
        settings = newSettings
        val current = snapshot
        publish(current.copy(thresholdDbfs = newSettings.thresholdDbfs, windowSeconds = newSettings.windowSeconds))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopFromUser()
            ACTION_START -> {
                settings = MonitorSettings(
                    thresholdDbfs = intent.getIntExtra(EXTRA_THRESHOLD, -25).coerceIn(-60, -5),
                    windowSeconds = intent.getIntExtra(EXTRA_WINDOW, 5).coerceIn(1, 60),
                    alertMode = AlertMode.entries.firstOrNull {
                        it.name == intent.getStringExtra(EXTRA_ALERT_MODE)
                    } ?: AlertMode.BOTH,
                )
                if (!running) startMonitoring()
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting microphone…"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required.")
            return
        }

        try {
            val sampleRate = SAMPLE_RATE
            val minimumBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimumBuffer <= 0) {
                fail("This device could not prepare microphone input.")
                return
            }
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(max(minimumBuffer, READ_SAMPLES * 2))
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                fail("Microphone initialization failed. Check device access and try again.")
                return
            }

            audioRecord = recorder
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                fail("The microphone did not start recording. Another app may be using it.")
                return
            }
            if (!wakeLock.isHeld) wakeLock.acquire()
            running = true
            startedAt = android.os.SystemClock.elapsedRealtime()
            publish(
                MonitorSnapshot(
                    active = true,
                    thresholdDbfs = settings.thresholdDbfs,
                    windowSeconds = settings.windowSeconds,
                ),
            )
            worker = Thread({ readLoop(sampleRate) }, "RoomtoneAudio").apply { start() }
        } catch (_: SecurityException) {
            fail("Microphone permission was denied or revoked.")
        } catch (_: Exception) {
            fail("Microphone unavailable. Check device access and try again.")
        }
    }

    private fun readLoop(sampleRate: Int) {
        var window = RollingPowerWindow(sampleRate, settings.windowSeconds)
        var windowSeconds = settings.windowSeconds
        val samples = ShortArray(READ_SAMPLES)
        while (running) {
            val recorder = audioRecord ?: break
            val count = try {
                recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
            } catch (_: Exception) {
                ERROR_READ
            }
            if (!running) break
            if (count <= 0) {
                failFromWorker(
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                        "Microphone input ended. Another app may have taken audio access."
                    } else {
                        "Microphone input stopped unexpectedly."
                    },
                )
                break
            }

            val currentSettings = settings
            if (currentSettings.windowSeconds != windowSeconds) {
                windowSeconds = currentSettings.windowSeconds
                window = RollingPowerWindow(sampleRate, windowSeconds)
            }
            val power = pcm16MeanSquare(samples, count)
            window.add(power, count)
            val shouldAlert = window.isAtOrAbove(currentSettings.thresholdDbfs.toDouble())
            updateAlert(shouldAlert, currentSettings.alertMode)

            val now = android.os.SystemClock.elapsedRealtime()
            val next = MonitorSnapshot(
                active = true,
                ready = window.isReady,
                alerting = shouldAlert,
                currentDbfs = powerToDbfs(power),
                averageDbfs = if (window.isReady) powerToDbfs(window.averagePower) else null,
                thresholdDbfs = currentSettings.thresholdDbfs,
                windowSeconds = currentSettings.windowSeconds,
                elapsedMillis = now - startedAt,
                error = alertWarning,
            )
            mainHandler.post { publish(next) }
            if (now - lastNotificationAt >= NOTIFICATION_UPDATE_MS) {
                lastNotificationAt = now
                val status = if (shouldAlert) "Threshold reached" else "Monitoring microphone"
                mainHandler.post { notificationManager.notify(NOTIFICATION_ID, buildNotification(status)) }
            }
        }
    }

    private fun updateAlert(shouldAlert: Boolean, mode: AlertMode) {
        if (shouldAlert) {
            if (activeAlertMode != null && activeAlertMode != mode) stopAlertOutputs()
            activeAlertMode = mode
            if (mode == AlertMode.TONE || mode == AlertMode.BOTH) startTone()
            if (mode == AlertMode.VIBRATION || mode == AlertMode.BOTH) startVibration()
        } else if (activeAlertMode != null) {
            stopAlertOutputs()
        }
    }

    private fun startTone() {
        if (toneGenerator != null || alertWarning != null) return
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioFocusRequest = request
            hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasAudioFocus) {
                alertWarning = "Alert tone unavailable while another app has audio focus."
                return
            }
            alertWarning = null
            val generator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGenerator = generator
            val repeat = object : Runnable {
                override fun run() {
                    if (running && toneGenerator === generator) {
                        generator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
                        mainHandler.postDelayed(this, 1_200)
                    }
                }
            }
            toneRepeater = repeat
            mainHandler.post(repeat)
        } catch (_: Exception) {
            alertWarning = "Alert tone output is unavailable on this device."
            releaseAudioFocus()
            toneGenerator?.release()
            toneGenerator = null
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        if (vibrationActive) return
        try {
            val deviceVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = deviceVibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                deviceVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 550, 250), 0))
            } else {
                deviceVibrator.vibrate(longArrayOf(0, 550, 250), 0)
            }
            vibrationActive = true
        } catch (_: Exception) {
            vibrator = null
        }
    }

    private fun stopAlertOutputs() {
        toneRepeater?.let(mainHandler::removeCallbacks)
        toneRepeater = null
        toneGenerator?.stopTone()
        toneGenerator?.release()
        toneGenerator = null
        releaseAudioFocus()
        vibrator?.cancel()
        vibrator = null
        vibrationActive = false
        activeAlertMode = null
        alertWarning = null
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun failFromWorker(message: String) {
        running = false
        mainHandler.post { fail(message) }
    }

    private fun fail(message: String) {
        stopCapture()
        publish(snapshot.copy(active = false, alerting = false, error = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopFromUser() {
        stopCapture()
        publish(MonitorSnapshot(thresholdDbfs = settings.thresholdDbfs, windowSeconds = settings.windowSeconds))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopCapture() {
        running = false
        val recorder = audioRecord
        audioRecord = null
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (_: Exception) {
            // AudioRecord may already have stopped after a device or permission change.
        }
        worker?.takeIf { it !== Thread.currentThread() }?.let {
            try {
                it.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
        try {
            recorder?.release()
        } catch (_: Exception) {
            // Release is best-effort after platform audio errors.
        }
        stopAlertOutputs()
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun publish(next: MonitorSnapshot) {
        snapshot = next
        observer?.invoke(next)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.notification_channel_description) },
            )
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, MonitorService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(this, CONTENT_REQUEST_CODE, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_roomtone)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.notification_stop), stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.roomtone.action.START"
        const val ACTION_STOP = "com.roomtone.action.STOP"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_WINDOW = "window"
        const val EXTRA_ALERT_MODE = "alert_mode"
        private const val CHANNEL_ID = "roomtone_monitor"
        private const val NOTIFICATION_ID = 42
        private const val STOP_REQUEST_CODE = 10
        private const val CONTENT_REQUEST_CODE = 11
        private const val SAMPLE_RATE = 16_000
        private const val READ_SAMPLES = 1_024
        private const val NOTIFICATION_UPDATE_MS = 1_500L
        private const val ERROR_READ = -10_000
    }
}