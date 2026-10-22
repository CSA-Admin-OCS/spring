package com.open.spring.mvc.person;

import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.open.spring.system.ExceptionHandlerAdvice;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// The password rule lives on Person (@Pattern on newPassword), and JPA runs it on save.
// These tests run the same validator JPA uses, without a database.
public class PasswordCheckTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<Person>> newPasswordViolations(String newPassword) {
        Person person = new Person();
        person.setNewPassword(newPassword);
        return validator.validateProperty(person, "newPassword");
    }

    @Test
    public void testStrongPassword() {
        assertTrue(newPasswordViolations("Password1!").isEmpty());
    }

    @Test
    public void testWeakPasswordsBreakTheRule() {
        for (String pw : new String[] {"password1!", "PASSWORD1!", "Password!", "Password1", "Pa1!"}) {
            Set<ConstraintViolation<Person>> violations = newPasswordViolations(pw);
            assertEquals(1, violations.size(), pw);
            assertEquals(Person.PASSWORD_RULE, violations.iterator().next().getMessage());
        }
    }

    @Test
    public void testDefaultPasswordInApplicationPropertiesMeetsTheRule() throws Exception {
        // Seed users and resets use person.default-password; JPA would reject a weak one at startup
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            props.load(in);
        }
        Matcher m = java.util.regex.Pattern.compile("\\$\\{DEFAULT_PASSWORD:(.+)\\}")
                .matcher(props.getProperty("person.default-password"));
        assertTrue(m.matches(), "person.default-password should be ${DEFAULT_PASSWORD:<default>}");
        assertTrue(newPasswordViolations(m.group(1)).isEmpty(), m.group(1));
    }

    @Test
    public void testNoNewPasswordMeansNothingToCheck() {
        // Unrelated profile edits leave newPassword null, so the stored hash is never checked
        assertTrue(newPasswordViolations(null).isEmpty());
    }

    @Test
    public void testSaveHandsThePlaintextToTheManagedCopyJpaValidates() {
        PersonJpaRepository repo = mock(PersonJpaRepository.class);
        // save() on an existing person is a merge: JPA hands back a different, managed copy
        Person managed = new Person();
        when(repo.save(any(Person.class))).thenReturn(managed);
        PersonDetailsService service = new PersonDetailsService();
        ReflectionTestUtils.setField(service, "passwordEncoder", new BCryptPasswordEncoder());
        ReflectionTestUtils.setField(service, "personJpaRepository", repo);

        Person person = new Person();
        person.setPassword("Password1!");
        service.save(person, false);

        assertEquals("Password1!", person.getNewPassword());
        assertTrue(new BCryptPasswordEncoder().matches("Password1!", person.getPassword()));
        assertEquals(1L, person.getTokenVersion());
        // merge skips @Transient fields, so the plaintext must reach the copy JPA validates
        assertEquals("Password1!", managed.getNewPassword());
        verify(repo).flush();
    }

    @Test
    public void testConstraintViolationBecomesA400WithTheRule() throws Exception {
        ConstraintViolationException e = new ConstraintViolationException(newPasswordViolations("weak"));
        ResponseEntity<String> response = new ExceptionHandlerAdvice().constraintViolationException(e);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(Person.PASSWORD_RULE, new org.json.JSONObject(response.getBody()).getString("error"));
    }
}
