package com.nullsobserver

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.graphics.Typeface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var list: LinearLayout
    private lateinit var analysisResult: TextView
    private lateinit var matchReport: TextView
    private val captureRequest = 4102
    private val screenshotRequest = 4103
    private val analysisKey = "last_screenshot_analysis"
    private val preferences by lazy { getSharedPreferences(RecordingService.PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshRecordings()
        syncRecordingState()
    }

    // All layout measurements use density-independent pixels so the UI scales
    // with the POCO F6 display density instead of using tiny raw pixel values.
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun buildScreen() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
            setBackgroundColor(0xFF101820.toInt())
        }

        val title = TextView(this).apply {
            text = "NULLS OBSERVER"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Local match recording & review"
            textSize = 16f
            setTextColor(0xFFB8C7D1.toInt())
            setPadding(0, dp(8), 0, dp(28))
        }
        status = TextView(this).apply {
            text = "Screen recording is OFF"
            textSize = 16f
            setTextColor(0xFFE4EDF2.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            minHeight = dp(60)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1C2A35.toInt())
        }
        toggle = Button(this).apply {
            text = "Turn recording ON"
            textSize = 17f
            isAllCaps = false
            minHeight = dp(64)
            minimumHeight = dp(64)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnClickListener { toggleRecording() }
            contentDescription = "Toggle screen recording on or off"
        }
        val refresh = Button(this).apply {
            text = "Refresh recordings"
            textSize = 16f
            isAllCaps = false
            minHeight = dp(56)
            minimumHeight = dp(56)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { refreshRecordings() }
        }
        val analyze = Button(this).apply {
            text = "Analyze game screenshot"
            textSize = 16f
            isAllCaps = false
            minHeight = dp(56)
            minimumHeight = dp(56)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { chooseScreenshot() }
        }
        val analysisHeading = TextView(this).apply {
            text = "Latest screenshot analysis"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(10))
        }
        analysisResult = TextView(this).apply {
            text = preferences.getString(analysisKey, "Choose a game screenshot to begin.")
            textSize = 15f
            setTextColor(0xFFD9E5EC.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF1C2A35.toInt())
        }
        val matchHeading = TextView(this).apply {
            text = "Latest match report"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(10))
        }
        matchReport = TextView(this).apply {
            text = preferences.getString(MatchAnalysisWorker.KEY_LAST_REPORT, "Record a match to generate an automatic report.")
            textSize = 15f
            setTextColor(0xFFD9E5EC.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF1C2A35.toInt())
        }
        val heading = TextView(this).apply {
            text = "Saved recordings"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(28), 0, dp(12))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        content.addView(title)
        content.addView(subtitle)
        content.addView(status, matchWidth().apply {
            bottomMargin = dp(16)
        })
        content.addView(toggle, matchWidth().apply {
            bottomMargin = dp(12)
        })
        content.addView(refresh, matchWidth().apply { bottomMargin = dp(10) })
        content.addView(analyze, matchWidth())
        content.addView(analysisHeading)
        content.addView(analysisResult, matchWidth())
        content.addView(matchHeading)
        content.addView(matchReport, matchWidth())
        content.addView(heading)
        content.addView(list)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            setBackgroundColor(0xFF101820.toInt())
        }
        setContentView(scroll)
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
        if (requestCode == screenshotRequest) {
            if (resultCode == RESULT_OK && data?.data != null) {
                analyzeScreenshot(data.data!!)
            } else {
                analysisResult.text = "No screenshot selected."
            }
            return
        }
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

    private fun chooseScreenshot() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, screenshotRequest)
    }

    private fun analyzeScreenshot(uri: Uri) {
        analysisResult.text = "Reading screenshot…"
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val image = InputImage.fromFilePath(this, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val extracted = result.text.trim()
                    val output = if (extracted.isBlank()) {
                        "No readable game text was detected. Try a clearer screenshot."
                    } else {
                        ScreenshotGameParser.analyze(result, image.width, image.height) +
                            "\n\nRECOGNIZED SCREENSHOT TEXT\n\n" + extracted
                    }
                    analysisResult.text = output
                    preferences.edit().putString(analysisKey, output).apply()
                    recognizer.close()
                }
                .addOnFailureListener { error ->
                    analysisResult.text = "Could not read this screenshot: " + (error.localizedMessage ?: "unknown error")
                    recognizer.close()
                }
        } catch (error: Exception) {
            analysisResult.text = "Could not open this image: " + (error.localizedMessage ?: "unknown error")
            recognizer.close()
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
                    text = "▶   $name"
                    textSize = 16f
                    setTextColor(0xFFD9E5EC.toInt())
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    minHeight = dp(60)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    background = selectableItemBackground()
                    setOnClickListener { openVideo(uri) }
                }
                list.addView(item, matchWidth().apply { bottomMargin = dp(6) })
            }
        }
        if (list.childCount == 0) {
            list.addView(TextView(this).apply {
                text = "No recordings yet."
                textSize = 16f
                setTextColor(0xFFB8C7D1.toInt())
                setPadding(dp(4), dp(14), dp(4), dp(14))
                minHeight = dp(56)
                gravity = Gravity.CENTER_VERTICAL
            })
        }
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val attrs = intArrayOf(android.R.attr.selectableItemBackground)
        val typedArray = obtainStyledAttributes(attrs)
        val drawable = typedArray.getDrawable(0)
        typedArray.recycle()
        return drawable
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
        if (::matchReport.isInitialized) {
            matchReport.text = preferences.getString(MatchAnalysisWorker.KEY_LAST_REPORT, "Record a match to generate an automatic report.")
        }
    }
}
