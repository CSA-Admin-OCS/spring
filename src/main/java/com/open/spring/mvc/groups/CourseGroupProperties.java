package com.open.spring.mvc.groups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * Canonical course groups, bound from the {@code courses.*} entries in
 * application.properties. {@code classGroups} order is significant: it drives the
 * seed order in ModelInit and the membership ordering in ClassGroupMembershipService.
 *
 * <p>Periods and course codes are keyed maps rather than an indexed list because
 * Spring merges map entries across property sources, so {@code .env} can override a
 * single period. An indexed list would have to be redeclared in full.
 */
@Component
@ConfigurationProperties(prefix = "courses")
@Validated
@Data
public class CourseGroupProperties {
    @NotEmpty(message = "courses.class-groups must list at least one course group")
    private List<String> classGroups = new ArrayList<>();

    private Map<String, String> periods = new LinkedHashMap<>();

    private Map<String, String> courseCodes = new LinkedHashMap<>();

    /** Configured group names, uppercased to match the profile class normalization. */
    public List<String> getGroupNames() {
        List<String> groupNames = new ArrayList<>(classGroups.size());
        for (String classGroup : classGroups) {
            groupNames.add(normalize(classGroup));
        }
        return groupNames;
    }

    /** Bell period for a group name, or null when none is configured. */
    public String periodFor(String groupName) {
        return lookup(periods, groupName);
    }

    /** Course code for a group, defaulting to the group name when unset. */
    public String courseFor(String groupName) {
        String courseCode = lookup(courseCodes, groupName);
        return courseCode == null ? normalize(groupName) : courseCode;
    }

    /**
     * Fails startup when a configured group has no period, which would otherwise
     * seed a group with a null period.
     */
    @PostConstruct
    void validatePeriodsAreComplete() {
        List<String> missingPeriods = new ArrayList<>();
        for (String groupName : getGroupNames()) {
            if (periodFor(groupName) == null) {
                missingPeriods.add(groupName);
            }
        }

        if (!missingPeriods.isEmpty()) {
            throw new IllegalStateException(
                "Missing courses.periods[...] entries for course groups: " + missingPeriods
            );
        }
    }

    private String lookup(Map<String, String> values, String groupName) {
        String normalizedName = normalize(groupName);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (normalize(entry.getKey()).equals(normalizedName) && value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalize(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }
}
