package com.example.zenscan.detection

import com.example.zenscan.model.CustomFilterRule
import com.example.zenscan.model.RegexMatch

object PiiPatternMatcher {
    const val LABEL_CREDIT_CARD = "CREDIT_CARD"
    const val LABEL_EMAIL = "EMAIL"
    const val LABEL_PHONE = "PHONE"
    const val LABEL_SSN = "SSN"

    private val creditCardRegex = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
    private val phoneRegex = Regex("""\+?\d{1,3}[-.\s]?\(?\d{2,4}\)?[-.\s]?\d{2,4}[-.\s]?\d{4,12}\b""")
    private val ssnRegex = Regex("""\b\d{3}-\d{2}-\d{4}\b""")

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

    fun findSSNs(text: String): List<RegexMatch> =
        ssnRegex.findAll(text)
            .map { RegexMatch(it.value, it.range.first, it.range.last + 1) }
            .toList()

    fun findPII(text: String, customRules: List<CustomFilterRule> = emptyList()): List<Pair<RegexMatch, String>> {
        val results = mutableListOf<Pair<RegexMatch, String>>()
        val creditCardMatches = findCreditCards(text)
        results.addAll(creditCardMatches.map { it to LABEL_CREDIT_CARD })
        results.addAll(findEmails(text).map { it to LABEL_EMAIL })
        results.addAll(findSSNs(text).map { it to LABEL_SSN })
        val phoneMatches = findPhoneNumbers(text).filter { phone ->
            creditCardMatches.none { cc ->
                phone.startIndex < cc.endIndex && phone.endIndex > cc.startIndex
            }
        }
        results.addAll(phoneMatches.map { it to LABEL_PHONE })

        // Process custom user-defined rules
        for (rule in customRules) {
            try {
                val pattern = if (rule.isRegex) rule.pattern else Regex.escape(rule.pattern)
                Regex(pattern, RegexOption.IGNORE_CASE).findAll(text).forEach { matchResult ->
                    results.add(RegexMatch(matchResult.value, matchResult.range.first, matchResult.range.last + 1) to rule.label)
                }
            } catch (e: Exception) {
                // Ignore matching exceptions (e.g. invalid regex patterns)
            }
        }

        return results
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
