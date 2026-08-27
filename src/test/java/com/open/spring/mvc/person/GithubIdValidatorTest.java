package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GithubIdValidatorTest {
    @Test
    public void rejectsExactlySevenDigits() {
        assertTrue(GithubIdValidator.isStudentIdUsedAsGithubId("1234567"));
        assertTrue(GithubIdValidator.isStudentIdUsedAsGithubId(" 1234567 "));
    }

    @Test
    public void allowsOtherGithubIdValues() {
        assertFalse(GithubIdValidator.isStudentIdUsedAsGithubId("123456"));
        assertFalse(GithubIdValidator.isStudentIdUsedAsGithubId("12345678"));
        assertFalse(GithubIdValidator.isStudentIdUsedAsGithubId("123456a"));
        assertFalse(GithubIdValidator.isStudentIdUsedAsGithubId("octocat"));
        assertFalse(GithubIdValidator.isStudentIdUsedAsGithubId(null));
    }
}
