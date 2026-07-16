package me.rerere.rikkahub.data.ai.trace

import kotlin.math.ceil

object PromptTokenEstimator {
    fun estimate(text: String): Int {
        var tokens = 0
        var latinRun = 0

        fun flushLatin() {
            if (latinRun > 0) {
                tokens += ceil(latinRun / 4.0).toInt()
                latinRun = 0
            }
        }

        text.codePoints().forEach { codePoint ->
            when {
                Character.isWhitespace(codePoint) -> flushLatin()
                isCjkKanaOrHangul(codePoint) -> {
                    flushLatin()
                    tokens += 1
                }
                Character.isLetterOrDigit(codePoint) -> latinRun += 1
                else -> {
                    flushLatin()
                    tokens += 1
                }
            }
        }
        flushLatin()
        return tokens
    }

    private fun isCjkKanaOrHangul(codePoint: Int): Boolean {
        return codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x3040..0x30FF ||
            codePoint in 0x31F0..0x31FF ||
            codePoint in 0x1100..0x11FF ||
            codePoint in 0x3130..0x318F ||
            codePoint in 0xAC00..0xD7AF
    }
}
