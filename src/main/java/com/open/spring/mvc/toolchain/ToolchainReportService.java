package com.open.spring.mvc.toolchain;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for managing the Toolchain Trail setup reports uploaded by students' terminals.
 * 
 * This service pairs a signed-in browser session with a local terminal using a
 * short-lived code. It validates the uploaded report format and persists the result.
 */
@Service
public class ToolchainReportService {
    /**
     * DTO pairing a student's browser with a local setup-report terminal.
     *
     * @param code the pairing code used by the terminal to submit a report
     * @param expiresAt the code's expiration time in Unix epoch milliseconds
     */
    public record PairingRecord(String code, long expiresAt) {
    }

    /** The duration for which a pairing code remains valid. */
    static final Duration PAIRING_CODE_LIFETIME = Duration.ofMinutes(30);
    
    /** The maximum allowed length for a report text to prevent abuse. */
    static final int MAX_REPORT_LENGTH = 20000;
    
    /** The fixed length of the generated pairing codes. */
    static final int PAIRING_CODE_LENGTH = 8;
    
    /** The alphabet used for pairing codes, excluding ambiguous characters (0/O, 1/I/L). */
    private static final String PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    
    /** Regex pattern to validate the format of a pairing code. */
    private static final Pattern PAIRING_CODE_FORMAT =
            Pattern.compile("[" + PAIRING_CODE_ALPHABET + "]{" + PAIRING_CODE_LENGTH + "}");

    /** Regex pattern to identify the 'Overall' status line in a report. */
    private static final Pattern OVERALL_LINE = Pattern.compile("(?m)^Overall:\\s*(PASS|WARN|FAIL)\\s*$");
    
    /** Regex pattern to identify the 'Summary' line in a report. */
    private static final Pattern SUMMARY_LINE =
            Pattern.compile("(?m)^Summary:\\s*(\\d{1,4}) passed, (\\d{1,4}) warned, (\\d{1,4}) failed\\s*$");

    private static final Logger logger = LoggerFactory.getLogger(ToolchainReportService.class);

    private final ToolchainReportJpaRepository repository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * Standard constructor using the system UTC clock.
     * 
     * @param repository The repository for persisting setup reports.
     */
    @Autowired
    public ToolchainReportService(ToolchainReportJpaRepository repository) {
        this(repository, Clock.systemUTC());
    }

    /**
     * Constructor for testing, allowing for a mocked clock.
     * 
     * @param repository The repository for persisting setup reports.
     * @param clock      The clock to use for time-based operations.
     */
    ToolchainReportService(ToolchainReportJpaRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Issues a new pairing code for a student.
     * If an unexpired code already exists for the student, it is reused and its 
     * expiration is extended.
     *
     * @param uid The unique identifier of the student.
     * @return A {@link PairingRecord} containing the code and its expiration timestamp.
     * @throws IllegalArgumentException if the uid is invalid.
     */
    public PairingRecord issuePairingCode(String uid) {
        requireUid(uid);
        long now = clock.millis();
        ToolchainReportEntity row = repository.findById(uid).orElseGet(() -> new ToolchainReportEntity(uid));
        if (!isPairingCodeActive(row, now)) {
            row.setPairingCode(newUnusedPairingCode());
        }
        row.setPairingCodeExpiresAt(now + PAIRING_CODE_LIFETIME.toMillis());
        repository.save(row);
        return new PairingRecord(row.getPairingCode(), row.getPairingCodeExpiresAt());
    }

    /**
     * Saves a report uploaded with a pairing code, replacing the student's earlier report.
     * The code stays valid until it expires so the student can fix things and run the verifier again.
     *
     * @param pairingCode The code provided by the terminal.
     * @param reportText  The raw text output from the terminal verifier.
     * @return true if the report was successfully saved; false if the code is unknown or expired.
     * @throws IllegalArgumentException when the text is not verifier output or exceeds length limits.
     */
    public boolean saveReport(String pairingCode, String reportText) {
        if (pairingCode == null || !PAIRING_CODE_FORMAT.matcher(pairingCode).matches()) {
            return false;
        }
        if (reportText == null || reportText.isBlank()) {
            throw new IllegalArgumentException("The report is empty.");
        }
        if (reportText.length() > MAX_REPORT_LENGTH) {
            throw new IllegalArgumentException("The report is longer than " + MAX_REPORT_LENGTH + " characters.");
        }
        Matcher overall = OVERALL_LINE.matcher(reportText);
        Matcher summary = SUMMARY_LINE.matcher(reportText);
        if (!overall.find() || !summary.find()) {
            throw new IllegalArgumentException("No verification results found in the report.");
        }

        long now = clock.millis();
        ToolchainReportEntity row = repository.findByPairingCode(pairingCode).orElse(null);
        if (row == null || !isPairingCodeActive(row, now)) {
            return false;
        }
        row.setOverall(overall.group(1));
        row.setPassed(Integer.valueOf(summary.group(1)));
        row.setWarned(Integer.valueOf(summary.group(2)));
        row.setFailed(Integer.valueOf(summary.group(3)));
        row.setReport(reportText);
        row.setReportedAt(now);
        repository.save(row);
        logger.info("CS Pathway setup report saved for {}: {}", row.getUid(), row.getOverall());
        return true;
    }

    /** 
     * Finds the student's latest report. 
     * 
     * @param uid The unique identifier of the student.
     * @return An {@link Optional} containing the report if it exists and contains data.
     */
    public Optional<ToolchainReportEntity> findReport(String uid) {
        requireUid(uid);
        return repository.findById(uid).filter(ToolchainReportEntity::hasReport);
    }

    /** 
     * Retrieves all reports that have been successfully uploaded, keyed by student UID.
     * Useful for the teacher's overview page.
     * 
     * @return A map of UIDs to their respective {@link ToolchainReportEntity}.
     */
    public Map<String, ToolchainReportEntity> getReportsByUid() {
        List<ToolchainReportEntity> reports = repository.findAllByReportedAtIsNotNull();
        return reports.stream().collect(Collectors.toMap(ToolchainReportEntity::getUid, Function.identity()));
    }

    /** Checks if the pairing code in the given row is non-null and not expired. */
    private static boolean isPairingCodeActive(ToolchainReportEntity row, long now) {
        return row.getPairingCode() != null
                && row.getPairingCodeExpiresAt() != null
                && row.getPairingCodeExpiresAt() > now;
    }

    /** Generates a new, unique pairing code that does not currently exist in the repository. */
    private String newUnusedPairingCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(PAIRING_CODE_LENGTH);
            for (int i = 0; i < PAIRING_CODE_LENGTH; i++) {
                builder.append(PAIRING_CODE_ALPHABET.charAt(random.nextInt(PAIRING_CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (repository.existsByPairingCode(code));
        return code;
    }

    /** Validates that the provided UID is not null or blank. */
    private static void requireUid(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("Student uid is required.");
        }
    }
}
