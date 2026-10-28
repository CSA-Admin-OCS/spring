package com.open.spring.mvc.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.open.spring.mvc.toolchain.ToolchainReportService.PairingRecord;

class ToolchainReportServiceTest {
    private static final String REPORT = """
            Environment verification for /Users/toby/blueprints_pages
            [PASS] git installed
            Summary: 14 passed, 1 warned, 2 failed
            Overall: FAIL

            Warnings and failures
            [WARN] jupyter available
            [FAIL] ruby installed
            """;

    @Mock
    private ToolchainReportJpaRepository repository;

    private MutableClock clock;
    private ToolchainReportService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clock = new MutableClock(Instant.parse("2026-10-06T16:00:00Z"));
        service = new ToolchainReportService(repository, clock);
        when(repository.save(any(ToolchainReportEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issuePairingCodeGivesANewStudentAnEightCharacterCodeThatExpiresInThirtyMinutes() {
        when(repository.findById("toby")).thenReturn(Optional.empty());

        PairingRecord pairingCode = service.issuePairingCode("toby");

        assertTrue(pairingCode.code().matches("[A-HJKMNP-Z2-9]{8}"));
        assertEquals(clock.millis() + Duration.ofMinutes(30).toMillis(), pairingCode.expiresAt());
    }

    @Test
    void issuePairingCodeKeepsAnUnexpiredCodeSoACopiedCommandStillWorks() {
        ToolchainReportEntity row = pairedRow("toby", "ABCD2345");
        when(repository.findById("toby")).thenReturn(Optional.of(row));
        clock.advance(Duration.ofMinutes(10));

        PairingRecord pairingCode = service.issuePairingCode("toby");

        assertEquals("ABCD2345", pairingCode.code());
        assertEquals(clock.millis() + Duration.ofMinutes(30).toMillis(), pairingCode.expiresAt());
    }

    @Test
    void issuePairingCodeReplacesAnExpiredCode() {
        ToolchainReportEntity row = pairedRow("toby", "ABCD2345");
        when(repository.findById("toby")).thenReturn(Optional.of(row));
        clock.advance(Duration.ofMinutes(31));

        assertNotEquals("ABCD2345", service.issuePairingCode("toby").code());
    }

    @Test
    void saveReportStoresTheReportAndItsCountsForTheStudentWhoOwnsTheCode() {
        ToolchainReportEntity row = pairedRow("toby", "ABCD2345");
        when(repository.findByPairingCode("ABCD2345")).thenReturn(Optional.of(row));

        assertTrue(service.saveReport("ABCD2345", REPORT));

        assertEquals("toby", row.getUid());
        assertEquals("FAIL", row.getOverall());
        assertEquals(14, row.getPassed());
        assertEquals(1, row.getWarned());
        assertEquals(2, row.getFailed());
        assertEquals(REPORT, row.getReport());
        assertEquals(clock.millis(), row.getReportedAt());
    }

    @Test
    void saveReportStoresALinuxOrWindowsAgentReport() {
        ToolchainReportEntity row = pairedRow("toby", "ABCD2345");
        when(repository.findByPairingCode("ABCD2345")).thenReturn(Optional.of(row));
        String linuxReport = """
                === LINUX SETUP CHECK v1 ===
                Started: 2026-10-15 09:30:00 PDT
                PASS git | Git
                FAIL vscode | VS Code code command | Install VS Code.
                Duration: 2 seconds
                Summary: 11 passed, 0 warned, 1 failed
                Overall: FAIL
                === END LINUX SETUP CHECK ===
                """;

        assertTrue(service.saveReport("ABCD2345", linuxReport));

        assertEquals("FAIL", row.getOverall());
        assertEquals(11, row.getPassed());
        assertEquals(1, row.getFailed());
        assertEquals(linuxReport, row.getReport());
    }

    @Test
    void saveReportAcceptsASecondRunWithTheSameCode() {
        ToolchainReportEntity row = pairedRow("toby", "ABCD2345");
        when(repository.findByPairingCode("ABCD2345")).thenReturn(Optional.of(row));
        service.saveReport("ABCD2345", REPORT);

        String fixed = "[PASS] ruby installed\nSummary: 17 passed, 0 warned, 0 failed\nOverall: PASS\n";
        assertTrue(service.saveReport("ABCD2345", fixed));

        assertEquals("PASS", row.getOverall());
        assertEquals(0, row.getFailed());
    }

    @Test
    void saveReportRefusesUnknownExpiredAndMalformedCodes() {
        ToolchainReportEntity row = pairedRow("toby", "ABCD2345");
        when(repository.findByPairingCode("ABCD2345")).thenReturn(Optional.of(row));
        when(repository.findByPairingCode("ZZZZ9999")).thenReturn(Optional.empty());

        assertFalse(service.saveReport("ZZZZ9999", REPORT));
        assertFalse(service.saveReport("abcd2345", REPORT));
        assertFalse(service.saveReport(null, REPORT));
        clock.advance(Duration.ofMinutes(31));
        assertFalse(service.saveReport("ABCD2345", REPORT));

        verify(repository, never()).save(any(ToolchainReportEntity.class));
    }

    @Test
    void saveReportRejectsTextThatIsNotVerifierOutput() {
        String tooLong = REPORT + "x".repeat(ToolchainReportService.MAX_REPORT_LENGTH);

        assertThrows(IllegalArgumentException.class, () -> service.saveReport("ABCD2345", ""));
        assertThrows(IllegalArgumentException.class, () -> service.saveReport("ABCD2345", "[PASS] git installed"));
        assertThrows(IllegalArgumentException.class, () -> service.saveReport("ABCD2345", tooLong));
        verify(repository, never()).save(any(ToolchainReportEntity.class));
    }

    @Test
    void findReportIsEmptyUntilTheTerminalHasUploadedOne() {
        when(repository.findById("toby")).thenReturn(Optional.of(pairedRow("toby", "ABCD2345")));

        assertTrue(service.findReport("toby").isEmpty());
    }

    private ToolchainReportEntity pairedRow(String uid, String code) {
        ToolchainReportEntity row = new ToolchainReportEntity(uid);
        row.setPairingCode(code);
        row.setPairingCodeExpiresAt(clock.millis() + ToolchainReportService.PAIRING_CODE_LIFETIME.toMillis());
        return row;
    }

    /** A clock the test moves by hand, so expiry is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
