package com.nullsobserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    companion object {
        const val ACTION_STOP = "com.nullsobserver.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PREFS = "nulls_observer_state"
        const val KEY_RECORDING = "recording_active"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 73
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outputUri: android.net.Uri? = null
    private var outputDescriptor: android.os.ParcelFileDescriptor? = null
    private var isRecording = false
    private var isStopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            stopCapture()
            return START_NOT_STICKY
        }

        return try {
            beginCapture(resultCode, resultData)
            START_NOT_STICKY
        } catch (error: Exception) {
            stopCapture()
            START_NOT_STICKY
        }
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtMost(2160)
        val height = metrics.heightPixels.coerceAtMost(3840)
        val density = metrics.densityDpi

        val filename = "NullsMatch_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NullsObserver")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create recording file")
        outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
            ?: error("Could not open recording file")

        recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder?.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width - (width % 2), height - (height % 2))
            setVideoFrameRate(30)
            setVideoEncodingBitRate(6_000_000)
            setOutputFile(outputDescriptor!!.fileDescriptor)
            prepare()
        }

        val activeProjection = projection ?: error("Screen capture session unavailable")
        activeProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, null)

        display = activeProjection.createVirtualDisplay(
            "NullsObserverCapture",
            width - (width % 2),
            height - (height % 2),
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder?.surface,
            null,
            null
        )
        recorder?.start()
        isRecording = true
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RECORDING, true).apply()
    }

    private fun stopCapture() {
        if (isStopping) return
        isStopping = true
        if (isRecording) {
            runCatching { recorder?.stop() }
            isRecording = false
        }
        runCatching { display?.release() }
        display = null
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null

        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }

        runCatching { outputDescriptor?.close() }
        outputDescriptor = null
        val completedUri = outputUri
        completedUri?.let { uri ->
            val values = ContentValues().apply {
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching { contentResolver.update(uri, values, null, null) }
            val request = OneTimeWorkRequestBuilder<MatchAnalysisWorker>()
                .setInputData(workDataOf(MatchAnalysisWorker.KEY_VIDEO_URI to uri.toString()))
                .build()
            WorkManager.getInstance(applicationContext).enqueue(request)
        }
        outputUri = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RECORDING, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen recording",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nulls Observer is recording")
            .setContentText("Tap Stop to turn recording off.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", pending).build())
            .build()
    }

    override fun onDestroy() {
        if (!isStopping && (isRecording || projection != null)) stopCapture()
        super.onDestroy()
    }
}
