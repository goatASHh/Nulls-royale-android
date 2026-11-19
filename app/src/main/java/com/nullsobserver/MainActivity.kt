package com.nullsobserver

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var list: LinearLayout
    private val captureRequest = 4102
    private val preferences by lazy { getSharedPreferences(RecordingService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshRecordings()
        syncRecordingState()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
            setBackgroundColor(0xFF101820.toInt())
        }

        val title = TextView(this).apply {
            text = "NULLS OBSERVER"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "Local match recording & review"
            textSize = 14f
            setTextColor(0xFFB8C7D1.toInt())
            setPadding(0, 6, 0, 24)
        }
        status = TextView(this).apply {
            text = "Screen recording is OFF"
            textSize = 14f
            setTextColor(0xFFE4EDF2.toInt())
            setPadding(0, 8, 0, 12)
        }
        toggle = Button(this).apply {
            text = "Turn recording ON"
            setOnClickListener { toggleRecording() }
        }
        val refresh = Button(this).apply {
            text = "Refresh recordings"
            setOnClickListener { refreshRecordings() }
        }
        val heading = TextView(this).apply {
            text = "Saved recordings"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 24, 0, 8)
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(toggle, matchWidth())
        root.addView(refresh, matchWidth())
        root.addView(heading)
        root.addView(list)
        setContentView(root)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun toggleRecording() {
        if (preferences.getBoolean(RecordingService.KEY_RECORDING, false)) {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            status.text = "Turning recording OFF…"
            toggle.isEnabled = false
        } else {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
        }
    }

    @Deprecated("Activity result API kept minimal for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen recording is OFF — permission was not granted."
            syncRecordingState()
            return
        }
        val service = Intent(this, RecordingService::class.java).apply {
            putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(RecordingService.EXTRA_RESULT_DATA, data)
        }
        try {
            startForegroundService(service)
            status.text = "Starting screen recording…"
        } catch (error: Exception) {
            status.text = "Could not start recording: ${error.localizedMessage ?: "unknown error"}"
            syncRecordingState()
        }
    }

    private fun syncRecordingState() {
        val active = preferences.getBoolean(RecordingService.KEY_RECORDING, false)
        toggle.isEnabled = true
        toggle.text = if (active) "Turn recording OFF" else "Turn recording ON"
        status.text = if (active) "Screen recording is ON" else "Screen recording is OFF"
    }

    private fun refreshRecordings() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH}=?"
        val args = arrayOf("Movies/NullsObserver/")
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val uri = Uri.withAppendedPath(collection, id.toString())
                val item = TextView(this).apply {
                    text = "▶  $name"
                    textSize = 14f
                    setTextColor(0xFFD9E5EC.toInt())
                    setPadding(4, 12, 4, 12)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { openVideo(uri) }
                }
                list.addView(item)
            }
        }
        if (list.childCount == 0) {
            list.addView(TextView(this).apply {
                text = "No recordings yet."
                setTextColor(0xFFB8C7D1.toInt())
                setPadding(4, 8, 4, 8)
            })
        }
    }

    private fun openVideo(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No video player is available.", Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refreshRecordings()
        if (::toggle.isInitialized) syncRecordingState()
    }
}
