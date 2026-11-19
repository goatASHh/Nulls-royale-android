package com.nullsobserver

import com.google.mlkit.vision.text.Text

/**
 * First spatially-aware Null's Royale snapshot parser.
 * OCR values are assigned by normalized screen position: opponent/red at the
 * top, player/blue at the bottom. This is a snapshot parser, not a full match AI.
 */
object ScreenshotGameParser {
    private data class LocatedLine(val text: String, val x: Float, val y: Float)

    fun analyze(ocr: Text, imageWidth: Int, imageHeight: Int): String {
        if (imageWidth <= 0 || imageHeight <= 0) return "Could not analyse this image: invalid dimensions."

        val lines = ocr.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                LocatedLine(
                    line.text.trim(),
                    (bounds.centerX().toFloat() / imageWidth).coerceIn(0f, 1f),
                    (bounds.centerY().toFloat() / imageHeight).coerceIn(0f, 1f)
                )
            }
        }.filter { it.text.isNotBlank() }

        fun valueIn(region: (LocatedLine) -> Boolean): String =
            lines.asSequence().filter(region).mapNotNull { line ->
                Regex("(?<![\\d])\\d{3,5}(?![\\d])")
                    .find(line.text.replace(",", ""))?.value
            }.firstOrNull() ?: "Not detected"

        val opponentKing = valueIn { it.y < 0.16f && it.x in 0.30f..0.72f }
        val opponentLeftTower = valueIn { it.y in 0.14f..0.34f && it.x < 0.40f }
        val playerKing = valueIn { it.y in 0.66f..0.86f && it.x in 0.30f..0.72f }
        val timer = lines.firstOrNull {
            it.x > 0.68f && it.y < 0.14f && Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(it.text)
        }?.text ?: "Not detected"
        val opponentName = lines.firstOrNull {
            it.x < 0.34f && it.y < 0.085f &&
                it.text.any(Char::isLetter) && !it.text.contains("clan", ignoreCase = true)
        }?.text ?: "Not detected"

        val notes = mutableListOf<String>()
        val ours = playerKing.toIntOrNull()
        val theirs = opponentKing.toIntOrNull()
        if (ours != null && theirs != null) {
            notes += when {
                ours < theirs -> "Your King Tower has less remaining HP than the opponent's."
                ours > theirs -> "Your King Tower has more remaining HP than the opponent's."
                else -> "Both King Towers show the same HP."
            }
        }
        if (opponentLeftTower != "Not detected") {
            notes += "The opponent's left Princess Tower is showing $opponentLeftTower HP."
        }
        if (timer != "Not detected") notes += "The visible match timer reads $timer."

        return buildString {
            appendLine("NULL'S ROYALE — MATCH SNAPSHOT")
            appendLine()
            appendLine("SIDE MAPPING")
            appendLine("Blue / bottom = You")
            appendLine("Red / top = Opponent")
            appendLine()
            appendLine("OPPONENT")
            appendLine("Name: $opponentName")
            appendLine("King Tower HP: $opponentKing")
            appendLine("Left Princess Tower HP: $opponentLeftTower")
            appendLine()
            appendLine("YOU")
            appendLine("King Tower HP: $playerKing")
            appendLine()
            appendLine("MATCH")
            appendLine("Timer: $timer")
            appendLine()
            appendLine("SNAPSHOT INSIGHTS")
            if (notes.isEmpty()) appendLine("Not enough positioned values were detected for a reliable comparison.")
            else notes.forEach { appendLine("• $it") }
            appendLine()
            append("Single-frame analysis cannot reliably determine the winner, troop history, or why a play succeeded or failed. That needs multiple frames and match-level analysis.")
        }
    }
}
