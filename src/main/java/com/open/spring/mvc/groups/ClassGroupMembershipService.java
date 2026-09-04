package com.open.spring.mvc.groups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@Service
public class ClassGroupMembershipService {
    private final GroupsJpaRepository groupsRepository;
    private final PersonJpaRepository personRepository;
    private final List<String> classGroupNames;

    public ClassGroupMembershipService(
            GroupsJpaRepository groupsRepository,
            PersonJpaRepository personRepository,
            CourseGroupProperties courseGroupProperties) {
        this.groupsRepository = groupsRepository;
        this.personRepository = personRepository;
        this.classGroupNames = List.copyOf(courseGroupProperties.getGroupNames());
    }

    /**
     * Makes the authenticated person's course-group memberships match their
     * profile classes. Memberships in unrelated groups are left untouched.
     */
    @Transactional
    public List<String> syncMemberships(String uid, List<String> classes) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("A user id is required");
        }

        Person person = personRepository.findByUid(uid);
        if (person == null) {
            throw new NoSuchElementException("Authenticated user was not found");
        }

        Set<String> requestedGroups = normalizeClasses(classes);
        Map<String, Groups> courseGroups = loadCourseGroups();

        for (String requestedGroup : requestedGroups) {
            if (courseGroups.get(requestedGroup) == null) {
                throw new NoSuchElementException(
                    "Course group '" + requestedGroup + "' was not found"
                );
            }
        }

        List<Groups> changedGroups = new ArrayList<>();
        for (String groupName : classGroupNames) {
            Groups group = courseGroups.get(groupName);
            if (group == null) {
                continue;
            }

            boolean shouldBeMember = requestedGroups.contains(groupName);
            boolean isMember = group.getGroupMembers().contains(person);

            if (shouldBeMember && !isMember) {
                group.addPerson(person);
                changedGroups.add(group);
            } else if (!shouldBeMember && isMember) {
                group.removePerson(person);
                changedGroups.add(group);
            }
        }

        if (!changedGroups.isEmpty()) {
            groupsRepository.saveAll(changedGroups);
        }

        return classGroupNames.stream()
            .filter(requestedGroups::contains)
            .toList();
    }

    private Set<String> normalizeClasses(List<String> classes) {
        Set<String> normalizedClasses = new LinkedHashSet<>();
        if (classes == null) {
            return normalizedClasses;
        }

        for (String className : classes) {
            if (className == null || className.isBlank()) {
                throw new IllegalArgumentException("Class names cannot be blank");
            }

            String normalizedClass = className.trim().toUpperCase(Locale.ROOT);
            if (!classGroupNames.contains(normalizedClass)) {
                throw new IllegalArgumentException(
                    "Unsupported class '" + className + "'"
                );
            }
            normalizedClasses.add(normalizedClass);
        }

        return normalizedClasses;
    }

    private Map<String, Groups> loadCourseGroups() {
        Map<String, Groups> courseGroups = new LinkedHashMap<>();
        for (String groupName : classGroupNames) {
            courseGroups.put(groupName, groupsRepository.findByName(groupName).orElse(null));
        }
        return courseGroups;
    }
}
