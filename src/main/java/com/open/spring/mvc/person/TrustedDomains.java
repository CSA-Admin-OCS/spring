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
// ModelInit.ensureMentorDomainsSeeded for how the starter file gets written.
public class TrustedDomains {
    private static final Logger logger = LoggerFactory.getLogger(TrustedDomains.class);

    public static final Path DOMAINS_FILE = Paths.get("volumes", "mentor-trusted-domains.txt");

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
}
