package com.hadi.smsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientMergeTest {
    private val normalize: (String) -> String? = { raw ->
        raw.filter(Char::isDigit).takeIf { it.length in 7..15 }
    }

    @Test fun duplicatesAcrossSourcesBecomeOneRecipient() {
        val merged = RecipientMerge.merge(
            listOf(
                Candidate("0123 456 789", "Test contact", Source.CONTACT),
                Candidate("0123456789", null, Source.SMS),
                Candidate("0123-456-789", "CSV label", Source.CSV)
            ), normalize
        )
        assertEquals(1, merged.size)
        assertEquals("Test contact", merged.single().name)
        assertTrue(merged.single().contacts)
        assertTrue(merged.single().sms)
        assertTrue(merged.single().csv)
    }

    @Test fun smsFirstStillGetsLaterContactName() {
        val merged = RecipientMerge.merge(
            listOf(
                Candidate("0123456789", null, Source.SMS),
                Candidate("0123456789", "Ali", Source.CONTACT)
            ), normalize
        )
        assertEquals("Ali", merged.single().name)
    }

    @Test fun invalidNumbersAreNotAdded() {
        val merged = RecipientMerge.merge(
            listOf(Candidate("support@example.org", "Invalid", Source.CONTACT)), normalize
        )
        assertTrue(merged.isEmpty())
    }

    @Test fun contactNameWinsOverCsvAlias() {
        val merged = RecipientMerge.merge(
            listOf(
                Candidate("0123456789", "Actual", Source.CONTACT),
                Candidate("0123456789", "Alias", Source.CSV)
            ), normalize
        )
        assertEquals("Actual", merged.single().name)
    }

    @Test fun sourceFlagsAreAccurate() {
        val merged = RecipientMerge.merge(
            listOf(Candidate("0123456789", null, Source.CSV)), normalize
        )
        assertFalse(merged.single().contacts)
        assertFalse(merged.single().sms)
        assertTrue(merged.single().csv)
    }
}
