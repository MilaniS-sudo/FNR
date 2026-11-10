package za.ac.richfield.fnr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import za.ac.richfield.fnr.security.Validators

/** Unit tests for the input rules in Phase 4, section 4.7.3. */
class ValidatorsTest {

    @Test fun strongPasswordIsAccepted() = assertNull(Validators.password("Study@2026"))

    @Test fun weakPasswordsAreRejected() {
        assertNotNull(Validators.password("short1!"))     // under 8 characters
        assertNotNull(Validators.password("alllower1!"))  // no upper case
        assertNotNull(Validators.password("NoDigits!!"))  // no number
        assertNotNull(Validators.password("NoSymbol12"))  // no symbol
    }

    @Test fun confirmMustMatch() {
        assertNull(Validators.confirm("Study@2026", "Study@2026"))
        assertEquals("Passwords do not match", Validators.confirm("Study@2026", "Study@2025"))
    }

    @Test fun names() {
        assertNull(Validators.name("Thabo"))
        assertNull(Validators.name("O'Neil-Smith"))
        assertNotNull(Validators.name("A"))
        assertNotNull(Validators.name("R2D2"))
        assertNotNull(Validators.name(""))
    }

    @Test fun subjectNameLength() {
        assertNull(Validators.subjectName("Software Engineering"))
        assertNotNull(Validators.subjectName("A"))
        assertNotNull(Validators.subjectName("x".repeat(61)))
    }

    @Test fun clipRules() {
        val recording = 600_000L // 10 minutes
        assertNull(Validators.clip(10_000, 40_000, recording))
        assertNotNull(Validators.clip(40_000, 10_000, recording))   // end before start
        assertNotNull(Validators.clip(10_000, 12_000, recording))   // under 5 s
        assertNotNull(Validators.clip(0, 200_000, recording))       // over 120 s
        assertNotNull(Validators.clip(590_000, 610_000, recording)) // past the end
    }

    @Test fun dateRange() {
        val now = System.currentTimeMillis()
        assertNull(Validators.dateRange(now - 86_400_000, now))
        assertNotNull(Validators.dateRange(now, now - 86_400_000))
    }

    @Test fun noteTitle() {
        assertNull(Validators.noteTitle("Primary key"))
        assertNotNull(Validators.noteTitle("PK"))
    }

    @Test fun realEmailAddressesAreAccepted() {
        assertNull(Validators.email("thabo.mokoena@gmail.com"))
        assertNull(Validators.email("402412405@my.richfield.ac.za"))
        assertNull(Validators.email("first.last+fnr@outlook.co.za"))
        assertNull(Validators.email("  student@yahoo.com  "))           // spaces around are trimmed
    }

    @Test fun badlyShapedEmailsAreRejected() {
        assertEquals("Enter your email", Validators.email(""))
        assertNotNull(Validators.email("thabo"))                       // no @
        assertNotNull(Validators.email("thabo@@gmail.com"))            // two @
        assertNotNull(Validators.email("@gmail.com"))                  // nothing before @
        assertNotNull(Validators.email("thabo@"))                      // nothing after @
        assertNotNull(Validators.email("thabo@gmail"))                 // no domain ending
        assertNotNull(Validators.email("tha bo@gmail.com"))            // space inside
        assertNotNull(Validators.email(".thabo@gmail.com"))            // starts with a dot
        assertNotNull(Validators.email("thabo.@gmail.com"))            // dot right before @
        assertNotNull(Validators.email("tha..bo@gmail.com"))           // two dots in a row
        assertNotNull(Validators.email("thabo@gmail..com"))            // empty domain part
        assertNotNull(Validators.email("thabo@-gmail.com"))            // part starts with a hyphen
        assertNotNull(Validators.email("thabo@gmail.c"))               // ending too short
        assertNotNull(Validators.email("thabo@gmail.c0m"))             // digit in the ending
        assertNotNull(Validators.email("thabo@gm_ail.com"))            // underscore in domain
        assertNotNull(Validators.email("a".repeat(65) + "@gmail.com")) // part before @ too long
    }

    @Test fun commonTyposGetASuggestion() {
        assertEquals("Did you mean thabo@gmail.com?", Validators.email("thabo@gmial.com"))
        assertEquals("Did you mean thabo@gmail.com?", Validators.email("thabo@gmail.con"))
        assertEquals("Did you mean thabo@hotmail.com?", Validators.email("thabo@hotmial.com"))
    }

    @Test fun fakeAndThrowAwayAddressesAreRejected() {
        assertEquals("Use a real email address", Validators.email("thabo@example.com"))
        assertEquals("Use a real email address", Validators.email("thabo@site.test"))
        assertEquals("Temporary email addresses aren't allowed", Validators.email("thabo@mailinator.com"))
    }
}
