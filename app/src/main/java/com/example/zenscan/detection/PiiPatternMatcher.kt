package com.example.zenscan.detection

import android.graphics.Rect
import com.example.zenscan.model.DetectedRegion
import com.example.zenscan.model.RegexMatch
import com.example.zenscan.model.RegionType
import com.example.zenscan.model.TextBlockBounds

object PiiPatternMatcher {

    private val creditCardRegex = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val phoneRegex = Regex("""\+?\d{1,3}[-.\s]?\(?\d{1,4}\)?[-.\s]?\d{1,4}[-.\s]?\d{1,9}""")

    fun findCreditCards(text: String): List<RegexMatch> =
        creditCardRegex.findAll(text)
            .map { it.value to it.range }
            .filter { (number, _) -> isValidLuhn(number.filter { c -> c.isDigit() }) }
            .map { (_, range) -> RegexMatch(text.substring(range), range.first, range.last + 1) }
            .toList()

    fun findEmails(text: String): List<RegexMatch> =
        emailRegex.findAll(text)
            .map { RegexMatch(it.value, it.range.first, it.range.last + 1) }
            .toList()

    fun findPhoneNumbers(text: String): List<RegexMatch> =
        phoneRegex.findAll(text)
            .map { RegexMatch(it.value, it.range.first, it.range.last + 1) }
            .toList()

    fun findPII(text: String): List<Pair<RegexMatch, String>> {
        val results = mutableListOf<Pair<RegexMatch, String>>()
        results.addAll(findCreditCards(text).map { it to "CREDIT_CARD" })
        results.addAll(findEmails(text).map { it to "EMAIL" })
        results.addAll(findPhoneNumbers(text).map { it to "PHONE" })
        return results
    }

    fun mapMatchesToRegions(
        matches: List<Pair<RegexMatch, String>>,
        textBlocks: List<TextBlockBounds>
    ): List<DetectedRegion> {
        val charToBlock = mutableListOf<Pair<Int, TextBlockBounds>>()
        var globalIndex = 0
        for (block in textBlocks) {
            for (i in block.text.indices) {
                charToBlock.add(globalIndex to block)
                globalIndex++
            }
            charToBlock.add(globalIndex to block)
            globalIndex++
        }

        return matches.map { (match, label) ->
            val matchedBlocks = charToBlock
                .filter { (idx, _) -> idx in match.startIndex until match.endIndex }
                .map { it.second }
                .distinct()

            val combinedRect = if (matchedBlocks.isEmpty()) {
                Rect(0, 0, 0, 0)
            } else {
                val first = matchedBlocks.first().boundingBox
                var left = first.left
                var top = first.top
                var right = first.right
                var bottom = first.bottom
                for (block in matchedBlocks) {
                    val r = block.boundingBox
                    left = minOf(left, r.left)
                    top = minOf(top, r.top)
                    right = maxOf(right, r.right)
                    bottom = maxOf(bottom, r.bottom)
                }
                Rect(left, top, right, bottom)
            }

            DetectedRegion(
                boundingBox = combinedRect,
                label = label,
                type = RegionType.PII_TEXT
            )
        }
    }

    fun isValidLuhn(number: String): Boolean {
        if (number.length < 13 || number.length > 19) return false
        var sum = 0
        var alternate = false
        for (i in number.indices.reversed()) {
            val digit = number[i] - '0'
            var n = digit
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }
}
