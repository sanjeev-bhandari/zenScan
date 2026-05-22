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
}