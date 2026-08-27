package com.open.spring.mvc.person;

import java.util.regex.Pattern;

public final class GithubIdValidator {
    private static final Pattern STUDENT_ID_PATTERN = Pattern.compile("^[0-9]{7}$");

    private GithubIdValidator() {
    }

    public static boolean isStudentIdUsedAsGithubId(String value) {
        return value != null && STUDENT_ID_PATTERN.matcher(value.trim()).matches();
    }
}
