package com.example.zenscan.detection

import org.junit.Assert.*
import org.junit.Test

class PiiPatternMatcherTest {

    // --- isValidLuhn ---

    @Test
    fun isValidLuhn_validVisa() {
        assertTrue(PiiPatternMatcher.isValidLuhn("4000056655665556"))
    }

    @Test
    fun isValidLuhn_validAmex() {
        assertTrue(PiiPatternMatcher.isValidLuhn("378282246310005"))
    }

    @Test
    fun isValidLuhn_validMastercard() {
        assertTrue(PiiPatternMatcher.isValidLuhn("5555555555554444"))
    }

    @Test
    fun isValidLuhn_invalid() {
        assertFalse(PiiPatternMatcher.isValidLuhn("1234567890123456"))
    }

    @Test
    fun isValidLuhn_tooShort() {
        assertFalse(PiiPatternMatcher.isValidLuhn("1234"))
    }

    @Test
    fun isValidLuhn_tooLong() {
        assertFalse(PiiPatternMatcher.isValidLuhn("12345678901234567890"))
    }

    @Test
    fun isValidLuhn_empty() {
        assertFalse(PiiPatternMatcher.isValidLuhn(""))
    }

    // --- findCreditCards ---

    @Test
    fun findCreditCards_validCard() {
        val text = "My card is 4000056655665556"
        val cards = PiiPatternMatcher.findCreditCards(text)
        assertEquals(1, cards.size)
        assertEquals("4000056655665556", cards[0].text)
    }

    @Test
    fun findCreditCards_invalidCard() {
        val text = "My card is 1234567890123456"
        val cards = PiiPatternMatcher.findCreditCards(text)
        assertTrue(cards.isEmpty())
    }

    @Test
    fun findCreditCards_noMatch() {
        val text = "Hello world, no numbers here"
        val cards = PiiPatternMatcher.findCreditCards(text)
        assertTrue(cards.isEmpty())
    }

    @Test
    fun findCreditCards_multipleCards() {
        val text = "Visa: 4000056655665556 and Amex: 378282246310005"
        val cards = PiiPatternMatcher.findCreditCards(text)
        assertEquals(2, cards.size)
    }

    @Test
    fun findCreditCards_withSpaces() {
        val text = "Card: 4000 0566 5566 5556"
        val cards = PiiPatternMatcher.findCreditCards(text)
        assertEquals(1, cards.size)
    }

    // --- findEmails ---

    @Test
    fun findEmails_standard() {
        val text = "Contact me at user@example.com"
        val emails = PiiPatternMatcher.findEmails(text)
        assertEquals(1, emails.size)
        assertEquals("user@example.com", emails[0].text)
    }

    @Test
    fun findEmails_subdomain() {
        val text = "Email: test@mail.co.uk"
        val emails = PiiPatternMatcher.findEmails(text)
        assertEquals(1, emails.size)
    }

    @Test
    fun findEmails_noMatch() {
        val text = "No email here"
        val emails = PiiPatternMatcher.findEmails(text)
        assertTrue(emails.isEmpty())
    }

    @Test
    fun findEmails_withSpecialChars() {
        val text = "Email: user.name+tag@example.org"
        val emails = PiiPatternMatcher.findEmails(text)
        assertEquals(1, emails.size)
    }

    @Test
    fun findEmails_invalidFormat() {
        val text = "Not an email: user@"
        val emails = PiiPatternMatcher.findEmails(text)
        assertTrue(emails.isEmpty())
    }

    // --- findPhoneNumbers ---

    @Test
    fun findPhone_usFormat() {
        val text = "Call me at (555) 123-4567"
        val phones = PiiPatternMatcher.findPhoneNumbers(text)
        assertEquals(1, phones.size)
    }

    @Test
    fun findPhone_international() {
        val text = "International: +1 555 123 4567"
        val phones = PiiPatternMatcher.findPhoneNumbers(text)
        assertEquals(1, phones.size)
    }

    @Test
    fun findPhone_noMatch() {
        val text = "No phone here"
        val phones = PiiPatternMatcher.findPhoneNumbers(text)
        assertTrue(phones.isEmpty())
    }

    @Test
    fun findPhone_withDots() {
        val text = "Phone: 555.123.4567"
        val phones = PiiPatternMatcher.findPhoneNumbers(text)
        assertEquals(1, phones.size)
    }

    // --- findPII ---

    @Test
    fun findPII_mixed() {
        val text = "Email: user@example.com Card: 4000056655665556 Phone: (555) 123-4567"
        val results = PiiPatternMatcher.findPII(text)
        assertEquals(3, results.size)
        val labels = results.map { it.second }.toSet()
        assertTrue(labels.contains(PiiPatternMatcher.LABEL_CREDIT_CARD))
        assertTrue(labels.contains(PiiPatternMatcher.LABEL_EMAIL))
        assertTrue(labels.contains(PiiPatternMatcher.LABEL_PHONE))
    }

    @Test
    fun findPII_noDuplicateForCreditCardAsPhone() {
        val text = "Card: 4000056655665556"
        val results = PiiPatternMatcher.findPII(text)
        val phoneMatches = results.filter { it.second == PiiPatternMatcher.LABEL_PHONE }
        assertTrue("Credit card should not also be matched as phone", phoneMatches.isEmpty())
    }

    @Test
    fun findPII_emptyText() {
        val results = PiiPatternMatcher.findPII("")
        assertTrue(results.isEmpty())
    }

    @Test
    fun findPII_noMatches() {
        val results = PiiPatternMatcher.findPII("Just some random text without sensitive info")
        assertTrue(results.isEmpty())
    }

    // --- Enhanced Security & Validation Tests ---

    @Test
    fun isValidCreditCard_rejectsAllZeros() {
        // All zeros passes naive Luhn (sum=0, 0%10==0), but is not a valid credit card
        assertFalse(PiiPatternMatcher.isValidCreditCard("0000000000000000"))
    }

    @Test
    fun isValidCreditCard_rejectsAllSameDigits() {
        assertFalse(PiiPatternMatcher.isValidCreditCard("1111111111111111"))
    }

    @Test
    fun findSSNs_validSSN() {
        val text = "User SSN is 123-45-6789"
        val ssns = PiiPatternMatcher.findSSNs(text)
        assertEquals(1, ssns.size)
        assertEquals("123-45-6789", ssns[0].text)
    }

    @Test
    fun findSSNs_invalidArea000() {
        val text = "Invalid: 000-45-6789"
        val ssns = PiiPatternMatcher.findSSNs(text)
        assertTrue(ssns.isEmpty())
    }

    @Test
    fun findSSNs_invalidArea666() {
        val text = "Invalid: 666-45-6789"
        val ssns = PiiPatternMatcher.findSSNs(text)
        assertTrue(ssns.isEmpty())
    }

    @Test
    fun findSSNs_invalidArea900() {
        val text = "Invalid: 912-45-6789"
        val ssns = PiiPatternMatcher.findSSNs(text)
        assertTrue(ssns.isEmpty())
    }

    @Test
    fun findSSNs_invalidGroup00() {
        val text = "Invalid: 123-00-6789"
        val ssns = PiiPatternMatcher.findSSNs(text)
        assertTrue(ssns.isEmpty())
    }

    @Test
    fun findSSNs_invalidSerial0000() {
        val text = "Invalid: 123-45-0000"
        val ssns = PiiPatternMatcher.findSSNs(text)
        assertTrue(ssns.isEmpty())
    }

    @Test
    fun findPhone_doesNotMatchIsoDate() {
        val text = "Signed on 2026-05-20 in document."
        val phones = PiiPatternMatcher.findPhoneNumbers(text)
        assertTrue("ISO date should not be recognized as a phone number", phones.isEmpty())
    }

    @Test
    fun findPII_customKeyword() {
        val text = "Invoice status: TOP_SECRET_CODE confirmed."
        val customRule = com.example.zenscan.model.CustomFilterRule(
            id = "1",
            pattern = "TOP_SECRET_CODE",
            isRegex = false,
            label = "CONFIDENTIAL"
        )
        val results = PiiPatternMatcher.findPII(text, listOf(customRule))
        assertEquals(1, results.size)
        assertEquals("CONFIDENTIAL", results[0].second)
        assertEquals("TOP_SECRET_CODE", results[0].first.text)
    }

    @Test
    fun findPII_customRegex() {
        val text = "Employee ID is EMP-9921 active."
        val customRule = com.example.zenscan.model.CustomFilterRule(
            id = "2",
            pattern = """EMP-\d{4}""",
            isRegex = true,
            label = "EMPLOYEE_ID"
        )
        val results = PiiPatternMatcher.findPII(text, listOf(customRule))
        assertEquals(1, results.size)
        assertEquals("EMPLOYEE_ID", results[0].second)
        assertEquals("EMP-9921", results[0].first.text)
    }

    @Test
    fun findPII_customRegexInvalidSyntaxHandledSafely() {
        val text = "Some test text"
        val invalidRule = com.example.zenscan.model.CustomFilterRule(
            id = "3",
            pattern = "[invalid(regex",
            isRegex = true,
            label = "ERROR_TEST"
        )
        // Should not throw or crash
        val results = PiiPatternMatcher.findPII(text, listOf(invalidRule))
        assertTrue(results.isEmpty())
    }

    @Test
    fun findPII_customRegexEmptyPatternHandledSafely() {
        val text = "Some test text"
        val emptyRule = com.example.zenscan.model.CustomFilterRule(
            id = "4",
            pattern = "",
            isRegex = true,
            label = "EMPTY"
        )
        val results = PiiPatternMatcher.findPII(text, listOf(emptyRule))
        assertTrue(results.isEmpty())
    }
}