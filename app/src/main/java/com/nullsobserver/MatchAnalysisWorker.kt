package com.nullsobserver

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Analyses a completed local recording. Frames are sampled from the saved MP4
 * after recording stops, so the app never needs a second MediaProjection.
 */
class MatchAnalysisWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    data class FrameStats(
        val atMs: Long,
        val playerKingHp: Int?,
        val opponentKingHp: Int?,
        val opponentLeftTowerHp: Int?,
        val timer: String?
    )

    override fun doWork(): Result {
        val source = inputData.getString(KEY_VIDEO_URI) ?: return Result.failure()
        val uri = runCatching { Uri.parse(source) }.getOrNull() ?: return Result.failure()
        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            retriever.setDataSource(applicationContext, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return Result.failure()
            if (duration < 1000L) return Result.failure()

            val frames = mutableListOf<FrameStats>()
            val intervalMs = 2500L
            var timeMs = 0L
            while (timeMs < duration && frames.size < MAX_FRAMES) {
                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bitmap != null) {
                    try {
                        val image = InputImage.fromBitmap(bitmap, 0)
                        val recognized = Tasks.await(recognizer.process(image), 12, TimeUnit.SECONDS)
                        frames += parseFrame(recognized, bitmap.width, bitmap.height, timeMs)
                    } finally {
                        bitmap.recycle()
                    }
                }
                timeMs += intervalMs
            }

            val report = buildReport(frames, duration)
            applicationContext.getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_REPORT, report)
                .putString(KEY_LAST_TIMELINE, JSONArray().apply {
                    frames.forEach { f ->
                        put(JSONObject().apply {
                            put("atMs", f.atMs)
                            put("playerKingHp", f.playerKingHp ?: JSONObject.NULL)
                            put("opponentKingHp", f.opponentKingHp ?: JSONObject.NULL)
                            put("opponentLeftTowerHp", f.opponentLeftTowerHp ?: JSONObject.NULL)
                            put("timer", f.timer ?: JSONObject.NULL)
                        })
                    }
                }.toString())
                .apply()
            Result.success()
        } catch (error: Exception) {
            applicationContext.getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_REPORT, "Match analysis could not be completed: ${error.localizedMessage ?: "unknown error"}")
                .apply()
            Result.retry()
        } finally {
            runCatching { recognizer.close() }
            runCatching { retriever.release() }
        }
    }

    private fun parseFrame(
        text: com.google.mlkit.vision.text.Text,
        width: Int,
        height: Int,
        atMs: Long
    ): FrameStats {
        data class Line(val value: String, val x: Float, val y: Float)
        val lines = text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                Line(line.text.trim(), box.centerX().toFloat() / width, box.centerY().toFloat() / height)
            }
        }
        fun number(predicate: (Line) -> Boolean): Int? =
            lines.firstOrNull(predicate)?.let { line ->
                Regex("\\d{3,5}").find(line.value.replace(",", ""))?.value?.toIntOrNull()
            }

        val timer = lines.firstOrNull {
            it.x > 0.68f && it.y < 0.16f && Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(it.value)
        }?.let { Regex("\\b\\d{1,2}:\\d{2}\\b").find(it.value)?.value }

        return FrameStats(
            atMs = atMs,
            playerKingHp = number { it.y in 0.66f..0.88f && it.x in 0.30f..0.72f },
            opponentKingHp = number { it.y < 0.17f && it.x in 0.30f..0.72f },
            opponentLeftTowerHp = number { it.y in 0.14f..0.35f && it.x < 0.42f },
            timer = timer
        )
    }

    private fun buildReport(frames: List<FrameStats>, durationMs: Long): String {
        if (frames.isEmpty()) return "MATCH ANALYSIS\nNo readable frames were found in this recording."
        val playerValues = frames.mapNotNull { it.playerKingHp }
        val opponentValues = frames.mapNotNull { it.opponentKingHp }
        val towerValues = frames.mapNotNull { it.opponentLeftTowerHp }
        val firstPlayer = playerValues.firstOrNull()
        val lastPlayer = playerValues.lastOrNull()
        val firstOpponent = opponentValues.firstOrNull()
        val lastOpponent = opponentValues.lastOrNull()
        val lines = mutableListOf<String>()

        lines += "NULL'S ROYALE — MATCH REPORT"
        lines += "Recording analysed: ${durationMs / 1000}s"
        lines += "Frames sampled: ${frames.size} (about every 2.5 seconds)"
        lines += ""
        lines += "YOU — BLUE / BOTTOM"
        lines += "King Tower HP: ${lastPlayer?.toString() ?: "Not detected"}"
        if (firstPlayer != null && lastPlayer != null) {
            lines += "HP change across sampled frames: ${lastPlayer - firstPlayer}"
        }
        lines += ""
        lines += "OPPONENT — RED / TOP"
        lines += "King Tower HP: ${lastOpponent?.toString() ?: "Not detected"}"
        if (firstOpponent != null && lastOpponent != null) {
            lines += "HP change across sampled frames: ${lastOpponent - firstOpponent}"
        }
        lines += "Opponent left Princess Tower: ${towerValues.lastOrNull()?.toString() ?: "Not detected"}"
        lines += ""
        lines += "AUTOMATED OBSERVATIONS"
        if (firstPlayer != null && lastPlayer != null && firstOpponent != null && lastOpponent != null) {
            val playerLoss = firstPlayer - lastPlayer
            val opponentLoss = firstOpponent - lastOpponent
            lines += "Your King Tower HP lost: $playerLoss"
            lines += "Opponent King Tower HP lost: $opponentLoss"
            lines += when {
                opponentLoss > playerLoss -> "The opponent's King Tower took more damage during the sampled recording."
                playerLoss > opponentLoss -> "Your King Tower took more damage during the sampled recording."
                else -> "Both King Towers took equal net damage during the sampled recording."
            }
        } else {
            lines += "Not enough King Tower readings were detected for a reliable damage comparison."
        }
        val lastTimer = frames.lastOrNull { it.timer != null }?.timer
        lines += "Last readable timer: ${lastTimer ?: "Not detected"}"
        lines += ""
        lines += "LIMITATIONS"
        lines += "This first pass tracks visible tower HP and timer only. It does not yet identify cards, troops, elixir trades, placements, or causes of mistakes. Observations are based on sampled frames, not every frame."
        return lines.joinToString("\n")
    }

    companion object {
        const val KEY_VIDEO_URI = "video_uri"
        const val KEY_LAST_REPORT = "last_match_report"
        const val KEY_LAST_TIMELINE = "last_match_timeline"
        private const val MAX_FRAMES = 100
    }
}
