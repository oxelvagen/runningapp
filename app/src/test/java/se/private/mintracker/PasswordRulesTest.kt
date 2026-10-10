package se.oxelv.mintracker

import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.data.PasswordRules

class PasswordRulesTest {
    @Test fun projectMinimumIsEnforced() {
        assertNotNull(PasswordRules.validate("", ""))
        assertNotNull(PasswordRules.validate("12345", "12345"))
        assertNull(PasswordRules.validate("123456", "123456"))
    }
    @Test fun confirmationMustMatchExactly() {
        assertNotNull(PasswordRules.validate("abcdef", "abcdeF"))
        assertNotNull(PasswordRules.validate("abcdef", "abcdef "))
        assertNull(PasswordRules.validate("En lång fras! 🐕", "En lång fras! 🐕"))
    }
    @Test fun doesNotInventCharacterRequirements() {
        assertNull(PasswordRules.validate("abcdef", "abcdef"))
        assertNull(PasswordRules.validate("123456789", "123456789"))
    }
}
