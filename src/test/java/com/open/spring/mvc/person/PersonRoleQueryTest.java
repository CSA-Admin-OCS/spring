package com.open.spring.mvc.person;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Asking a person which roles they have: isMentor, isAdmin, isTeacher, isStudent, over the roles they hold.
 */
public class PersonRoleQueryTest {

    private static Person with(String... roleNames) {
        Person person = new Person();
        List<PersonRole> roles = new ArrayList<>();
        for (String name : roleNames) {
            roles.add(new PersonRole(name));
        }
        person.setRoles(roles);
        return person;
    }

    @Test
    public void aPersonWithNoRolesIsNone() {
        Person person = with();
        assertFalse(person.isAdmin());
        assertFalse(person.isTeacher());
        assertFalse(person.isStudent());
        assertFalse(person.isMentor());
    }

    @Test
    public void eachQuestionIsTrueOnlyForItsOwnRole() {
        assertTrue(with("ROLE_ADMIN").isAdmin());
        assertTrue(with("ROLE_TEACHER").isTeacher());
        assertTrue(with("ROLE_STUDENT").isStudent());
        assertTrue(with("ROLE_MENTOR").isMentor());
        Person mentor = with("ROLE_USER", "ROLE_MENTOR");
        assertTrue(mentor.isMentor());
        assertFalse(mentor.isAdmin());
        assertFalse(mentor.isTeacher());
        assertFalse(mentor.isStudent());
    }

    @Test
    public void aPersonCanHaveSeveralRoles() {
        Person person = with("ROLE_USER", "ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN");
        assertTrue(person.isStudent() && person.isTeacher() && person.isAdmin());
        assertFalse(person.isMentor());
    }

    @Test
    public void aRoleNameThatOnlyLooksLikeOneIsNotOne() {
        assertFalse(with("ROLE_MENTORS").isMentor());
        assertFalse(with("role_mentor").isMentor());
        assertFalse(with("MENTOR").isMentor());
    }

    @Test
    public void theQuestionsAddNothingToThePersonJson() throws Exception {
        // Person is serialised by every person endpoint: a boolean getter would become a new JSON field
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(with("ROLE_ADMIN", "ROLE_MENTOR")));
        for (String field : Set.of("admin", "teacher", "student", "mentor")) {
            assertFalse(json.has(field), field);
        }
        assertTrue(json.has("roles"));
    }
}
