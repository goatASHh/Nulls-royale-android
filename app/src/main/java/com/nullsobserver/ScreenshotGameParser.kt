package com.nullsobserver

/**
 * Conservative parser for Null's Royale / Clash Royale-style result screenshots.
 * It only reports fields when OCR contains an explicit, recognizable label.
 */
object ScreenshotGameParser {
    fun analyze(ocrText: String): String {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val normalized = lines.joinToString("\n").uppercase()

        val outcome = when {
            Regex("\\b(VICTORY|YOU WIN|WINNER)\\b").containsMatchIn(normalized) -> "Victory"
            Regex("\\b(DEFEAT|YOU LOST|LOSS)\\b").containsMatchIn(normalized) -> "Defeat"
            Regex("\\b(DRAW|TIE)\\b").containsMatchIn(normalized) -> "Draw"
            else -> null
        }

        val trophyLine = lines.firstOrNull {
            Regex("(?i)\\b(troph(?:y|ies))\\b").containsMatchIn(it)
        }
        val trophyValue = trophyLine?.let {
            Regex("[+−-]?\\s*\\d{1,4}").find(it)?.value?.replace(" ", "")
        }

        val crownLine = lines.firstOrNull {
            Regex("(?i)\\b(crowns?|tower)\\b").containsMatchIn(it)
        }
        val crownScore = crownLine?.let {
            Regex("\\b([0-3])\\s*[-:]\\s*([0-3])\\b").find(it)?.value
        }

        val playerLine = lines.firstOrNull {
            Regex("(?i)^((player|you|opponent|enemy)\\s*[:：-]).+").containsMatchIn(it)
        }

        return buildString {
            appendLine("MATCH SCREEN ANALYSIS")
            appendLine()
            appendLine("Result: ${outcome ?: "Not detected"}")
            appendLine("Trophy change: ${trophyValue ?: "Not detected"}")
            appendLine("Crown score: ${crownScore ?: "Not detected"}")
            appendLine("Player label: ${playerLine ?: "Not detected"}")
            appendLine()
            append("Only explicit text labels are used. Missing values are left blank rather than guessed.")
        }
    }
}
