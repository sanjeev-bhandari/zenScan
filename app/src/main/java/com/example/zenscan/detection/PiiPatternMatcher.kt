package com.example.zenscan.detection

import com.example.zenscan.model.CustomFilterRule
import com.example.zenscan.model.RegexMatch

object PiiPatternMatcher {
    const val LABEL_CREDIT_CARD = "CREDIT_CARD"
    const val LABEL_EMAIL = "EMAIL"
    const val LABEL_PHONE = "PHONE"
    const val LABEL_SSN = "SSN"

    // Safe credit card regex: 13-19 digits with optional single space or hyphen separators, ending on a digit
    private val creditCardRegex = Regex("""\b(?:\d[ -]?){12,18}\d\b""")

    // RFC-compliant email regex bounded by word boundaries
    private val emailRegex = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    // Phone number regex supporting international and standard domestic formats
    private val phoneRegex = Regex("""\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{2,4}\)?[-.\s]?\d{3,4}[-.\s]?\d{4}\b""")

    // Real US SSN format: 3-2-4 digits with hyphens or spaces
    // Rules: First 3 cannot be 000, 666, or 900-999; Middle 2 cannot be 00; Last 4 cannot be 0000
    private val ssnRegex = Regex("""\b(?!000|666|9\d{2})\d{3}[- ](?!00)\d{2}[- ](?!0000)\d{4}\b""")

    // Common ISO / domestic date patterns to prevent false-positive phone matches (e.g. 2024-10-28)
    private val dateRegex = Regex("""\b(?:19|20)\d{2}[-/.](?:0[1-9]|1[0-2])[-/.](?:0[1-9]|[12]\d|3[01])\b""")

    private fun MatchResult.toRegexMatch() = RegexMatch(value, range.first, range.last + 1)

    fun findCreditCards(text: String): List<RegexMatch> {
        if (text.isBlank()) return emptyList()
        return creditCardRegex.findAll(text)
            .filter { isValidCreditCard(it.value.filter(Char::isDigit)) }
            .map { it.toRegexMatch() }
            .toList()
    }

    fun findEmails(text: String): List<RegexMatch> =
        if (text.isBlank()) emptyList() else emailRegex.findAll(text).map { it.toRegexMatch() }.toList()

    fun findPhoneNumbers(text: String): List<RegexMatch> {
        if (text.isBlank()) return emptyList()
        val dateRanges = dateRegex.findAll(text).map { it.range }.toList()

        return phoneRegex.findAll(text)
            .filter { matchResult ->
                val digitsCount = matchResult.value.count(Char::isDigit)
                if (digitsCount !in 7..15) return@filter false

                val overlapsDate = dateRanges.any { dateRange ->
                    matchResult.range.first < dateRange.last + 1 && matchResult.range.last + 1 > dateRange.first
                }
                !overlapsDate
            }
            .map { it.toRegexMatch() }
            .toList()
    }

    fun findSSNs(text: String): List<RegexMatch> =
        if (text.isBlank()) emptyList() else ssnRegex.findAll(text).map { it.toRegexMatch() }.toList()

    fun findPII(text: String, customRules: List<CustomFilterRule> = emptyList()): List<Pair<RegexMatch, String>> {
        if (text.isBlank()) return emptyList()

        val results = mutableListOf<Pair<RegexMatch, String>>()

        // 1. Credit Cards
        val creditCardMatches = findCreditCards(text)
        results.addAll(creditCardMatches.map { it to LABEL_CREDIT_CARD })

        // 2. SSNs
        val ssnMatches = findSSNs(text)
        results.addAll(ssnMatches.map { it to LABEL_SSN })

        // 3. Emails
        val emailMatches = findEmails(text)
        results.addAll(emailMatches.map { it to LABEL_EMAIL })

        // 4. Phone Numbers (filtered to avoid overlapping with Credit Cards or SSNs)
        val highConfidenceRanges = (creditCardMatches + ssnMatches).map { it.startIndex until it.endIndex }
        val phoneMatches = findPhoneNumbers(text).filter { phone ->
            highConfidenceRanges.none { range ->
                phone.startIndex < range.last + 1 && phone.endIndex > range.first
            }
        }
        results.addAll(phoneMatches.map { it to LABEL_PHONE })

        // 5. Custom user-defined rules with safe regex execution
        for (rule in customRules) {
            val patternStr = rule.pattern.trim()
            if (patternStr.isEmpty()) continue

            try {
                val regex = if (rule.isRegex) {
                    if (patternStr.length > 200) continue
                    Regex(patternStr, RegexOption.IGNORE_CASE)
                } else {
                    Regex(Regex.escape(patternStr), RegexOption.IGNORE_CASE)
                }

                var matchCount = 0
                val label = rule.label.ifBlank { "CUSTOM" }
                for (match in regex.findAll(text)) {
                    if (match.value.isEmpty()) break
                    results.add(match.toRegexMatch() to label)
                    matchCount++
                    if (matchCount >= 200) break
                }
            } catch (e: Exception) {
                // Ignore malformed custom patterns
            }
        }

        return results
    }

    /**
     * Checks if a numeric digit sequence is a plausible credit card:
     * 1. Length must be between 13 and 19 digits.
     * 2. Must not be all identical digits (e.g. 0000000000000000).
     * 3. Must pass the standard Luhn checksum algorithm.
     */
    fun isValidCreditCard(number: String): Boolean {
        if (number.length !in 13..19) return false
        if (number.all { it == number[0] }) return false
        return isValidLuhn(number)
    }

    /**
     * Standard Luhn checksum algorithm for numeric strings.
     */
    fun isValidLuhn(number: String): Boolean {
        if (number.length !in 13..19) return false
        if (!number.all { it.isDigit() }) return false

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

