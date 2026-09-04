package com.open.spring.mvc.groups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

class ClassGroupMembershipServiceTest {
    private GroupsJpaRepository groupsRepository;
    private PersonJpaRepository personRepository;

    private ClassGroupMembershipService service;
    private Map<String, Optional<Groups>> groupsByName;
    private List<List<Groups>> savedGroupBatches;
    private Person person;
    private Groups csa;
    private Groups csp;
    private Groups csh;
    private Groups csse;

    @BeforeEach
    void setUp() {
        person = new Person();
        person.setUid("student");
        csa = group("CSA");
        csp = group("CSP");
        csh = group("CSH");
        csse = group("CSSE");

        groupsByName = new HashMap<>();
        groupsByName.put("CSA", Optional.of(csa));
        groupsByName.put("CSP", Optional.of(csp));
        groupsByName.put("CSH", Optional.of(csh));
        groupsByName.put("CSSE", Optional.of(csse));
        savedGroupBatches = new ArrayList<>();

        groupsRepository = repositoryStub(
            GroupsJpaRepository.class,
            (methodName, arguments) -> switch (methodName) {
                case "findByName" -> groupsByName.get((String) arguments[0]);
                case "saveAll" -> {
                    @SuppressWarnings("unchecked")
                    List<Groups> groups = new ArrayList<>((List<Groups>) arguments[0]);
                    savedGroupBatches.add(groups);
                    yield groups;
                }
                default -> throw new UnsupportedOperationException(methodName);
            }
        );
        personRepository = repositoryStub(
            PersonJpaRepository.class,
            (methodName, arguments) -> {
                if ("findByUid".equals(methodName)) {
                    return "student".equals(arguments[0]) ? person : null;
                }
                throw new UnsupportedOperationException(methodName);
            }
        );
        service = new ClassGroupMembershipService(
            groupsRepository,
            personRepository,
            courseGroupProperties("CSA", "CSP", "CSH", "CSSE")
        );
    }

    @Test
    void syncMembershipsJoinsSelectedGroupsAndLeavesDeselectedCourseGroups() {
        csh.addPerson(person);
        Groups unrelatedGroup = group("Robotics");
        unrelatedGroup.addPerson(person);

        List<String> memberships = service.syncMemberships("student", List.of("csa", "CSP"));

        assertEquals(List.of("CSA", "CSP"), memberships);
        assertTrue(csa.getGroupMembers().contains(person));
        assertTrue(csp.getGroupMembers().contains(person));
        assertFalse(csh.getGroupMembers().contains(person));
        assertTrue(unrelatedGroup.getGroupMembers().contains(person));
        assertEquals(1, savedGroupBatches.size());
        assertEquals(List.of(csa, csp, csh), savedGroupBatches.get(0));
    }

    @Test
    void syncMembershipsMapsEverySupportedClassToItsSameNamedGroup() {
        for (String className : List.of("CSA", "CSP", "CSH", "CSSE")) {
            service.syncMemberships("student", List.of(className));

            for (Map.Entry<String, Optional<Groups>> entry : groupsByName.entrySet()) {
                assertEquals(
                    entry.getKey().equals(className),
                    entry.getValue().orElseThrow().getGroupMembers().contains(person)
                );
            }
        }
    }

    @Test
    void syncMembershipsRejectsUnsupportedClassesWithoutChangingGroups() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.syncMemberships("student", List.of("Biology"))
        );

        assertFalse(csa.getGroupMembers().contains(person));
        assertTrue(savedGroupBatches.isEmpty());
    }

    @Test
    void syncMembershipsFailsBeforeChangingMembershipWhenSelectedGroupIsMissing() {
        csh.addPerson(person);
        groupsByName.put("CSP", Optional.empty());

        assertThrows(
            NoSuchElementException.class,
            () -> service.syncMemberships("student", List.of("CSP"))
        );

        assertTrue(csh.getGroupMembers().contains(person));
        assertTrue(savedGroupBatches.isEmpty());
    }

    @Test
    void syncMembershipsRejectsClassesThatAreNotInTheConfiguredCourseGroups() {
        ClassGroupMembershipService narrowedService = new ClassGroupMembershipService(
            groupsRepository,
            personRepository,
            courseGroupProperties("CSA", "CSP")
        );

        assertEquals(List.of("CSA"), narrowedService.syncMemberships("student", List.of("CSA")));
        assertThrows(
            IllegalArgumentException.class,
            () -> narrowedService.syncMemberships("student", List.of("CSH"))
        );
        assertFalse(csh.getGroupMembers().contains(person));
    }

    private Groups group(String name) {
        Groups group = new Groups();
        group.setName(name);
        return group;
    }

    private CourseGroupProperties courseGroupProperties(String... names) {
        CourseGroupProperties properties = new CourseGroupProperties();
        properties.setClassGroups(List.of(names));
        return properties;
    }

    @SuppressWarnings("unchecked")
    private <T> T repositoryStub(Class<T> repositoryType, RepositoryCall call) {
        return (T) Proxy.newProxyInstance(
            repositoryType.getClassLoader(),
            new Class<?>[] {repositoryType},
            (proxy, method, arguments) -> call.invoke(method.getName(), arguments)
        );
    }

    @FunctionalInterface
    private interface RepositoryCall {
        Object invoke(String methodName, Object[] arguments);
    }
}
