package com.open.spring.mvc.person;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Checks a verified email's domain against an admin-editable whitelist of trusted
// business domains, used for the mentor signup flow's optional "verify a business
// email" step (PersonApiController.postPerson). The whitelist lives in the externally
// mounted volumes/ directory (see docker-compose.yml) rather than src/main/resources,
// specifically so an admin can add a domain without a rebuild/redeploy -- see
// ensureSeeded() for how the starter file gets written. This class owns the file's
// whole lifecycle (path, seeding, reading) rather than splitting it across classes.
public class TrustedDomains {
    private static final Logger logger = LoggerFactory.getLogger(TrustedDomains.class);

    private static final Path DOMAINS_FILE = Paths.get("volumes", "mentor-trusted-domains.txt");

    // Re-reads the file on every call rather than caching it -- call volume here is
    // bounded by signup rate, and this avoids any stale-cache bug if an admin edits the
    // file while the app is running.
    public static boolean isTrusted(String email) {
        if (email == null || !email.contains("@")) {
            return false;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase();
        if (domain.isBlank()) {
            return false;
        }

        List<String> whitelist;
        try {
            whitelist = Files.readAllLines(DOMAINS_FILE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("AUDIT mentor_domain_check_failed reason=file_read_error msg={}", e.getMessage());
            return false;
        }

        for (String line : whitelist) {
            String trusted = line.trim().toLowerCase();
            if (trusted.isEmpty() || trusted.startsWith("#")) {
                continue;
            }
            // Suffix match, same style as the existing "@stu.powayusd.com" check --
            // "mail.google.com" matches a whitelisted "google.com".
            if (domain.equals(trusted) || domain.endsWith("." + trusted)) {
                return true;
            }
        }
        return false;
    }

    // Writes a starter domains file on first boot only -- never overwrites it if it
    // already exists, so an admin's edits survive restarts. Called once from
    // ModelInit alongside the ROLE_MENTOR/ROLE_PENDING role seeding.
    public static void ensureSeeded() throws IOException {
        if (Files.exists(DOMAINS_FILE)) {
            return;
        }
        Path parent = DOMAINS_FILE.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String[] starterDomains = {
            // Tech
            "google.com", "microsoft.com", "amazon.com", "apple.com", "meta.com",
            "ibm.com", "oracle.com", "salesforce.com", "adobe.com", "intel.com",
            "cisco.com", "nvidia.com", "qualcomm.com", "hp.com", "dell.com",
            "vmware.com", "sap.com", "workday.com", "servicenow.com", "atlassian.com",
            "netflix.com", "airbnb.com", "uber.com", "linkedin.com", "shopify.com",
            // Finance
            "jpmorgan.com", "jpmorganchase.com", "goldmansachs.com", "morganstanley.com",
            "bankofamerica.com", "wellsfargo.com", "citi.com", "blackrock.com",
            "visa.com", "mastercard.com", "paypal.com", "americanexpress.com",
            "capitalone.com", "schwab.com", "fidelity.com", "vanguard.com",
            // Consulting / professional services
            "mckinsey.com", "bain.com", "bcg.com", "deloitte.com", "pwc.com",
            "ey.com", "kpmg.com", "accenture.com",
            // Other well-known
            "boeing.com", "lockheedmartin.com", "generalelectric.com", "ge.com",
            "tesla.com", "disney.com", "nike.com", "starbucks.com", "target.com",
            "walmart.com", "jnj.com", "pfizer.com",
        };

        StringBuilder content = new StringBuilder();
        content.append("# Trusted business email domains for the mentor signup flow's\n");
        content.append("# optional \"verify a business email\" OAuth step.\n");
        content.append("# One domain per line. Lines starting with # are ignored.\n");
        content.append("# Matching is a case-insensitive suffix match, so \"google.com\" here\n");
        content.append("# also matches \"mail.google.com\".\n");
        content.append("# Edit this file directly and restart to add/remove trusted domains --\n");
        content.append("# it will not be overwritten once it exists.\n\n");
        for (String domain : starterDomains) {
            content.append(domain).append('\n');
        }

        Files.writeString(DOMAINS_FILE, content.toString(), StandardCharsets.UTF_8);
        logger.info("Seeded starter {} ({} domains)", DOMAINS_FILE, starterDomains.length);
    }
}
