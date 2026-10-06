package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TrustedDomainsTest {
    private static final List<String> DOMAINS = List.of("# comment", "", "Google.com", "  ibm.com  ");

    @Test
    void listedDomainIsTrustedRegardlessOfCase() {
        assertTrue(TrustedDomains.isTrusted("Pat@GOOGLE.com", DOMAINS));
        assertTrue(TrustedDomains.isTrusted("pat@ibm.com", DOMAINS));
    }

    @Test
    void subdomainOfListedDomainIsTrusted() {
        assertTrue(TrustedDomains.isTrusted("pat@mail.google.com", DOMAINS));
    }

    @Test
    void lookalikeDomainIsNotTrusted() {
        assertFalse(TrustedDomains.isTrusted("pat@notgoogle.com", DOMAINS));
        assertFalse(TrustedDomains.isTrusted("pat@google.com.evil.io", DOMAINS));
    }

    @Test
    void commentsAndBlankLinesNeverMatch() {
        assertFalse(TrustedDomains.isTrusted("pat@comment", DOMAINS));
        assertFalse(TrustedDomains.isTrusted("pat@", DOMAINS));
    }

    @Test
    void missingOrMalformedEmailIsNotTrusted() {
        assertFalse(TrustedDomains.isTrusted(null, DOMAINS));
        assertFalse(TrustedDomains.isTrusted("no-at-sign", DOMAINS));
    }
}
