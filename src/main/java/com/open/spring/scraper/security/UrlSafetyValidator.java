package com.open.spring.scraper.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class UrlSafetyValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "::1", "0.0.0.0"
    );

    private static final Set<String> ALLOWED_HEADERS = Set.of(
            "user-agent", "accept", "accept-language", "referer"
    );

    public void validateTargetUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Only http and https urls are allowed");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host is missing");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(normalizedHost)) {
            throw new IllegalArgumentException("Local/internal addresses are not allowed");
        }

        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new IllegalArgumentException("Only ports 80 and 443 are allowed");
        }

        for (InetAddress address : resolveAll(host)) {
            if (isInternalAddress(address)) {
                throw new IllegalArgumentException("Private/internal network targets are blocked");
            }
        }
    }

    public void validateHeaders(Set<String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        Set<String> invalidHeaders = new HashSet<>();
        for (String header : headers) {
            if (header == null) {
                continue;
            }
            String normalized = header.toLowerCase(Locale.ROOT).trim();
            if (!ALLOWED_HEADERS.contains(normalized)) {
                invalidHeaders.add(header);
            }
        }

        if (!invalidHeaders.isEmpty()) {
            throw new IllegalArgumentException(
                    "Disallowed headers: " + invalidHeaders + ". Allowed headers: " + ALLOWED_HEADERS
            );
        }
    }

    private InetAddress[] resolveAll(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve host: " + host);
        }
    }

    private boolean isInternalAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }

        if (address instanceof Inet4Address ipv4) {
            byte[] bytes = ipv4.getAddress();
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 10) {
                return true;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            return first == 192 && second == 168;
        }

        if (address instanceof Inet6Address ipv6) {
            return ipv6.isSiteLocalAddress() || ipv6.isLinkLocalAddress() || ipv6.isLoopbackAddress();
        }
        return false;
    }
}
