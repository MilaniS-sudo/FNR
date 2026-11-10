package za.ac.richfield.fnr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import za.ac.richfield.fnr.security.EmailDomainChecker

/** Unit tests for reading DNS-over-HTTPS replies in the email domain check (FR-USR-02). */
class EmailDomainCheckerTest {

    @Test fun readsMailServers() {
        val json = """{"Status":0,"Answer":[{"name":"gmail.com.","type":15,"TTL":3600,"data":"5 gmail-smtp-in.l.google.com."},
            {"name":"gmail.com.","type":15,"TTL":3600,"data":"10 alt1.gmail-smtp-in.l.google.com."}]}"""
        val answer = EmailDomainChecker.parse(json, EmailDomainChecker.TYPE_MX)!!
        assertEquals(0, answer.status)
        assertEquals(2, answer.records.size)
    }

    @Test fun readsDomainThatDoesNotExist() {
        val json = """{"Status":3,"Authority":[{"name":"com.","type":6,"TTL":900,"data":"a.gtld-servers.net. x 1 2 3 4 5"}]}"""
        val answer = EmailDomainChecker.parse(json, EmailDomainChecker.TYPE_MX)!!
        assertEquals(EmailDomainChecker.NXDOMAIN, answer.status)
        assertTrue(answer.records.isEmpty())
    }

    @Test fun ignoresRecordsOfOtherTypes() {
        val json = """{"Status":0,"Answer":[{"name":"x.com.","type":5,"TTL":60,"data":"y.com."}]}"""
        assertTrue(EmailDomainChecker.parse(json, EmailDomainChecker.TYPE_MX)!!.records.isEmpty())
    }

    @Test fun rejectsReplyThatIsNotDns() = assertNull(EmailDomainChecker.parse("<html>blocked</html>", 15))

    @Test fun recognisesNullMx() {
        assertTrue(EmailDomainChecker.isNullMx("0 ."))
        assertFalse(EmailDomainChecker.isNullMx("10 mx.example.org."))
    }
}
