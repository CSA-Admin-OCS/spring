package com.open.spring.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.mvc.person.PersonJpaRepository;

/** The JWT must live as long as its cookie, or users stay "logged in" with a dead token. */
class JwtTokenUtilExpiryTest {

    private static final long SEVEN_DAYS = 604800;

    private JwtTokenUtil tokenUtil(long maxAgeSeconds) {
        JwtTokenUtil util = new JwtTokenUtil();
        ReflectionTestUtils.setField(util, "secret", Base64.getEncoder().encodeToString(new byte[64]));
        ReflectionTestUtils.setField(util, "personJpaRepository", mock(PersonJpaRepository.class));
        ReflectionTestUtils.setField(util, "tokenValiditySeconds", maxAgeSeconds);
        return util;
    }

    private long secondsUntilExpiry(JwtTokenUtil util) {
        UserDetails user = User.withUsername("toby").password("x").authorities("ROLE_STUDENT").build();
        String token = util.generateToken(user, List.of("ROLE_STUDENT"));
        Date expiration = util.getExpirationDateFromToken(token);
        return (expiration.getTime() - System.currentTimeMillis()) / 1000;
    }

    @Test
    void tokenExpiresWithTheCookieNotAfterTwelveHours() {
        long seconds = secondsUntilExpiry(tokenUtil(SEVEN_DAYS));
        assertTrue(seconds > SEVEN_DAYS - 60 && seconds <= SEVEN_DAYS,
                "expected about 7 days, got " + seconds + " seconds");
    }

    @Test
    void tokenFollowsAShorterConfiguredCookieAge() {
        long seconds = secondsUntilExpiry(tokenUtil(3600));
        assertTrue(seconds > 3600 - 60 && seconds <= 3600, "expected about 1 hour, got " + seconds + " seconds");
    }
}
