package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CourseGroupPropertiesTest {
    @Test
    void mapsEachConfiguredGroupToItsPeriodInDeclarationOrder() {
        CourseGroupProperties properties = properties(
            List.of("CSA", "CSP", "CSH", "CSSE"),
            Map.of("CSA", "2", "CSP", "3", "CSH", "2", "CSSE", "1")
        );

        assertEquals(List.of("CSA", "CSP", "CSH", "CSSE"), properties.getGroupNames());
        assertEquals("2", properties.periodFor("CSA"));
        assertEquals("3", properties.periodFor("CSP"));
        assertEquals("2", properties.periodFor("CSH"));
        assertEquals("1", properties.periodFor("CSSE"));
    }

    @Test
    void normalizesConfiguredNamesAndLookupsToUppercase() {
        CourseGroupProperties properties = properties(List.of(" csa "), Map.of("csa", "2"));

        assertEquals(List.of("CSA"), properties.getGroupNames());
        assertEquals("2", properties.periodFor("csa"));
        assertNull(properties.periodFor("CSP"));
    }

    @Test
    void defaultsCourseCodeToGroupNameUnlessConfigured() {
        CourseGroupProperties properties = properties(List.of("CSA", "CSP"), Map.of("CSA", "2", "CSP", "3"));
        properties.setCourseCodes(Map.of("CSP", "APCSP"));

        assertEquals("CSA", properties.courseFor("CSA"));
        assertEquals("APCSP", properties.courseFor("CSP"));
    }

    @Test
    void failsFastWhenAConfiguredGroupHasNoPeriod() {
        CourseGroupProperties properties = properties(List.of("CSA", "CSSE"), Map.of("CSA", "2"));

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            properties::validatePeriodsAreComplete
        );
        assertEquals(
            "Missing courses.periods[...] entries for course groups: [CSSE]",
            failure.getMessage()
        );
    }

    private CourseGroupProperties properties(List<String> classGroups, Map<String, String> periods) {
        CourseGroupProperties properties = new CourseGroupProperties();
        properties.setClassGroups(classGroups);
        properties.setPeriods(periods);
        return properties;
    }
}
