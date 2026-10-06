package com.open.spring.mvc.person;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business-email domains a mentor may verify with. The list is volumes/mentor-trusted-domains.txt,
 * which docker-compose mounts, so an admin can edit it without a redeploy. It is read on every call
 * because signups are rare and an edit then applies at once.
 */
public class TrustedDomains {
    private static final Logger logger = LoggerFactory.getLogger(TrustedDomains.class);

    private static final Path DOMAINS_FILE = Paths.get("volumes", "mentor-trusted-domains.txt");

    public static boolean isTrusted(String email) {
        try {
            return isTrusted(email, Files.readAllLines(DOMAINS_FILE, StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Mentor domain check failed: cannot read {}", DOMAINS_FILE, e);
            return false;
        }
    }

    /** True when the email's domain is a listed domain or a subdomain of one ("mail.google.com" for "google.com"). */
    static boolean isTrusted(String email, List<String> domainLines) {
        if (email == null || !email.contains("@")) {
            return false;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (domain.isBlank()) {
            return false;
        }
        for (String line : domainLines) {
            String trusted = line.trim().toLowerCase(Locale.ROOT);
            if (trusted.isEmpty() || trusted.startsWith("#")) {
                continue;
            }
            if (domain.equals(trusted) || domain.endsWith("." + trusted)) {
                return true;
            }
        }
        return false;
    }
}
