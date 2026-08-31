package com.open.spring.mvc.groups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@RestController
@RequestMapping("/api/groups")
public class GroupsApiController {

    @Autowired
    private GroupsJpaRepository groupsRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private GroupChatService groupChatService;

    // ===== DTOs =====
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupCreateDto {
        private String name;
        private String period;
        private String course;        
        private List<Long> memberIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupUpdateDto {
        private String name;
        private String period;
        private String course;        
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BulkGroupCreateDto {
        private List<GroupCreateDto> groups;
    }

    // ===== Helper Methods =====
    private Map<String, Object> buildGroupResponse(Groups group) {
        Map<String, Object> groupMap = new LinkedHashMap<>();
        groupMap.put("id", group.getId());
        groupMap.put("name", group.getName());
        groupMap.put("period", group.getPeriod());
        groupMap.put("course", group.getCourse());

        List<Map<String, Object>> membersList = new ArrayList<>();
        List<Object[]> memberRows = groupsRepository.findGroupMembersRaw(group.getId());

        for (Object[] row : memberRows) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", ((Number) row[0]).longValue());
            member.put("uid", (String) row[1]);
            member.put("name", (String) row[2]);
            member.put("email", (String) row[3]);
            membersList.add(member);
        }

        groupMap.put("members", membersList);
        return groupMap;
    }

    private List<Map<String, Object>> buildMentorsList(Long groupId) {
        List<Map<String, Object>> mentorsList = new ArrayList<>();
        List<Object[]> mentorRows = groupsRepository.findGroupMentorsRaw(groupId);

        for (Object[] row : mentorRows) {
            Map<String, Object> mentor = new LinkedHashMap<>();
            mentor.put("id", ((Number) row[0]).longValue());
            mentor.put("uid", (String) row[1]);
            mentor.put("name", (String) row[2]);
            mentor.put("email", (String) row[3]);
            mentorsList.add(mentor);
        }

        return mentorsList;
    }

    // In-controller authority scan, mirroring PersonViewController's admin check --
    // there is no @PreAuthorize on this controller's other endpoints.
    private boolean hasAnyAuthority(Authentication authentication, String... roles) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            return false;
        }
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        List<String> roleList = java.util.Arrays.asList(roles);
        return userDetails.getAuthorities().stream()
            .anyMatch(authority -> roleList.contains(authority.getAuthority()));
    }

    // ===== GET Operations =====

    /**
     * GET /api/groups - Get all groups with their members.
     * Admin/teacher see every group; a mentor sees only the groups they mentor;
     * every other authenticated role is unchanged (sees all groups, as before).
     */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getAllGroups(Authentication authentication) {
        try {
            List<Groups> groups;

            if (!hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")
                    && hasAnyAuthority(authentication, "ROLE_MENTOR")) {
                String mentorUid = ((UserDetails) authentication.getPrincipal()).getUsername();
                // Union of mentored groups and groups they belong to, so this listing agrees
                // with the chat ACL (member OR mentor). Scoping to mentored groups alone made
                // a student who is also a mentor lose every group they are a member of from
                // the list, while still being allowed into those same chats by id.
                // An external mentor has no memberships, so for them this is unchanged.
                Map<Long, Groups> byId = new LinkedHashMap<>();
                for (Groups g : groupsRepository.findGroupsByMentorUid(mentorUid)) {
                    byId.put(g.getId(), g);
                }
                for (Groups g : groupsRepository.findGroupsByPersonUid(mentorUid)) {
                    byId.putIfAbsent(g.getId(), g);
                }
                groups = new ArrayList<>(byId.values());
            } else {
                groups = groupsRepository.findAll();
            }

            List<Map<String, Object>> result = new ArrayList<>();

            for (Groups group : groups) {
                result.add(buildGroupResponse(group));
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * GET /api/groups/{id} - Get a single group by ID with members
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getGroupById(@PathVariable Long id) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            return new ResponseEntity<>(buildGroupResponse(groupOpt.get()), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * GET /api/groups/person/{personId} - Get all groups containing a specific person
     */
    @GetMapping("/person/{personId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getGroupsByPersonId(@PathVariable Long personId) {
        try {
            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }

            List<Groups> groups = groupsRepository.findGroupsByPersonId(personId);
            List<Map<String, Object>> result = new ArrayList<>();

            for (Groups group : groups) {
                result.add(buildGroupResponse(group));
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * GET /api/groups/search?name={searchTerm} - Search groups by name
     * Returns a list of groups whose names contain the search term (case-insensitive)
     */
    @GetMapping("/search")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> searchGroupsByName(
            @org.springframework.web.bind.annotation.RequestParam(value = "name", required = true) String searchTerm) {
        try {
            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                return new ResponseEntity<>(
                    new ArrayList<>(),
                    HttpStatus.OK
                );
            }

            List<Groups> groups = groupsRepository.searchByName(searchTerm.trim());
            List<Map<String, Object>> result = new ArrayList<>();

            for (Groups group : groups) {
                result.add(buildGroupResponse(group));
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ===== POST Operations =====

    /**
     * POST /api/groups - Create a new group with optional initial members
     * Request body: { "name": "groupname", "period": "1", "memberIds": [1, 2, 3] }
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody GroupCreateDto dto) {
        try {
            if (dto.getName() == null || dto.getName().isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group name is required"),
                    HttpStatus.BAD_REQUEST
                );
            }

            if (groupsRepository.findByName(dto.getName()).isPresent()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group with name '" + dto.getName() + "' already exists"),
                    HttpStatus.CONFLICT
                );
            }

            Groups group = new Groups();
            group.setName(dto.getName());
            group.setPeriod(dto.getPeriod());
            group.setCourse(dto.getCourse());

            Groups savedGroup = groupsRepository.save(group);

            // Add members if provided
            if (dto.getMemberIds() != null) {
                for (Long personId : dto.getMemberIds()) {
                    Optional<Person> personOpt = personRepository.findById(personId);
                    if (personOpt.isPresent()) {
                        savedGroup.addPerson(personOpt.get());
                    }
                }
                savedGroup = groupsRepository.save(savedGroup);
            }

            // Initialize S3 folder structure for the new group
            groupChatService.initGroupStorage(savedGroup.getName());

            return new ResponseEntity<>(buildGroupResponse(savedGroup), HttpStatus.CREATED);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }


    /**
     * POST /api/groups/bulk - Bulk create multiple groups
     * Request body: { "groups": [{ "name": "...", "period": "...", "memberIds": [...] }, ...] }
     */
    @PostMapping("/bulk")
    @Transactional
    public ResponseEntity<Map<String, Object>> bulkCreateGroups(@RequestBody BulkGroupCreateDto dto) {
        try {
            List<Map<String, Object>> created = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            if (dto.getGroups() == null || dto.getGroups().isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "No groups provided"),
                    HttpStatus.BAD_REQUEST
                );
            }

            for (GroupCreateDto groupDto : dto.getGroups()) {
                try {
                    if (groupsRepository.findByName(groupDto.getName()).isPresent()) {
                        errors.add("Group with name '" + groupDto.getName() + "' already exists");
                        continue;
                    }

                    Groups group = new Groups();
                    group.setName(groupDto.getName());
                    group.setPeriod(groupDto.getPeriod());
                    group.setCourse(groupDto.getCourse());

                    Groups savedGroup = groupsRepository.save(group);

                    if (groupDto.getMemberIds() != null) {
                        for (Long personId : groupDto.getMemberIds()) {
                            Optional<Person> personOpt = personRepository.findById(personId);
                            if (personOpt.isPresent()) {
                                savedGroup.addPerson(personOpt.get());
                            }
                        }
                        savedGroup = groupsRepository.save(savedGroup);
                    }

                    // Initialize S3 folder structure for the new group
                    groupChatService.initGroupStorage(savedGroup.getName());

                    created.add(buildGroupResponse(savedGroup));
                } catch (Exception e) {
                    errors.add("Failed to create group '" + groupDto.getName() + "': " + e.getMessage());
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("created", created);
            if (!errors.isEmpty()) {
                response.put("errors", errors);
            }

            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    // ===== PUT Operations =====

    /**
     * PUT /api/groups/{id} - Update group name and/or period
     * Request body: { "name": "newname", "period": "2" }
     */
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateGroup(
            @PathVariable Long id,
            @RequestBody GroupUpdateDto dto) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();

            if (dto.getName() != null && !dto.getName().isEmpty()) {
                group.setName(dto.getName());
            }
            if (dto.getPeriod() != null) {
                group.setPeriod(dto.getPeriod());
            }
            if (dto.getCourse() != null) {
                group.setCourse(dto.getCourse());
            }


            Groups updatedGroup = groupsRepository.save(group);
            return new ResponseEntity<>(buildGroupResponse(updatedGroup), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    // ===== DELETE Operations =====


    
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteGroup (@PathVariable Long id) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            groupsRepository.deleteById(id);
            return new ResponseEntity<>(
                Map.of("message", "Group deleted successfully"),
                HttpStatus.OK
            );
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }



    /**
     * DELETE /api/groups/{id}/members/{personId} - Remove a single person from a group
     */
    @DeleteMapping("/{id}/members/{personId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removePersonFromGroup(
            @PathVariable Long id,
            @PathVariable Long personId) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();
            Person person = personOpt.get();

            if (!group.getGroupMembers().contains(person)) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not in group"),
                    HttpStatus.CONFLICT
                );
            }

            group.removePerson(person);
            groupsRepository.save(group);

            return new ResponseEntity<>(buildGroupResponse(group), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * POST /api/groups/{id}/members/{personId} - Add a single person to a group
     */
    @PostMapping("/{id}/members/{personId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> addPersonToGroup(
            @PathVariable Long id,
            @PathVariable Long personId) {
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();
            Person person = personOpt.get();

            if (group.getGroupMembers().contains(person)) {
                return new ResponseEntity<>(
                    Map.of("error", "Person already in group"),
                    HttpStatus.CONFLICT
                );
            }

            group.addPerson(person);
            groupsRepository.save(group);

            return new ResponseEntity<>(buildGroupResponse(group), HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    // ===== Group Mentors =====

    /**
     * GET /api/groups/{id}/mentors - Get the mentors for a group
     */
    @GetMapping("/{id}/mentors")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getGroupMentors(@PathVariable Long id) {
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        return new ResponseEntity<>(buildMentorsList(id), HttpStatus.OK);
    }

    /**
     * GET /api/groups/mentor/{personId} - Get all groups mentored by a specific person
     */
    @GetMapping("/mentor/{personId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getGroupsByMentorId(@PathVariable Long personId) {
        try {
            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }

            List<Groups> groups = groupsRepository.findGroupsByMentorId(personId);
            List<Map<String, Object>> result = new ArrayList<>();

            for (Groups group : groups) {
                result.add(buildGroupResponse(group));
            }

            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * POST /api/groups/{id}/mentors/{personId} - Add a mentor to a group (admin/teacher only)
     */
    @PostMapping("/{id}/mentors/{personId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> addMentorToGroup(
            @PathVariable Long id,
            @PathVariable Long personId,
            Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")) {
            return new ResponseEntity<>(
                Map.of("error", "Forbidden"),
                HttpStatus.FORBIDDEN
            );
        }
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();
            Person person = personOpt.get();

            if (group.getGroupMentors().contains(person)) {
                return new ResponseEntity<>(
                    Map.of("error", "Person already a mentor of this group"),
                    HttpStatus.CONFLICT
                );
            }

            group.addMentor(person);
            groupsRepository.save(group);

            return new ResponseEntity<>(
                Map.of("groupId", group.getId(), "mentors", buildMentorsList(id)),
                HttpStatus.OK
            );
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    /**
     * DELETE /api/groups/{id}/mentors/{personId} - Remove a mentor from a group (admin/teacher only)
     */
    @DeleteMapping("/{id}/mentors/{personId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> removeMentorFromGroup(
            @PathVariable Long id,
            @PathVariable Long personId,
            Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")) {
            return new ResponseEntity<>(
                Map.of("error", "Forbidden"),
                HttpStatus.FORBIDDEN
            );
        }
        try {
            Optional<Groups> groupOpt = groupsRepository.findById(id);
            if (groupOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Group not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Optional<Person> personOpt = personRepository.findById(personId);
            if (personOpt.isEmpty()) {
                return new ResponseEntity<>(
                    Map.of("error", "Person not found"),
                    HttpStatus.NOT_FOUND
                );
            }

            Groups group = groupOpt.get();
            Person person = personOpt.get();

            if (!group.getGroupMentors().contains(person)) {
                return new ResponseEntity<>(
                    Map.of("error", "Person is not a mentor of this group"),
                    HttpStatus.CONFLICT
                );
            }

            group.removeMentor(person);
            groupsRepository.save(group);

            return new ResponseEntity<>(
                Map.of("groupId", group.getId(), "mentors", buildMentorsList(id)),
                HttpStatus.OK
            );
        } catch (Exception e) {
            return new ResponseEntity<>(
                Map.of("error", e.getMessage()),
                HttpStatus.BAD_REQUEST
            );
        }
    }

    // ===== Group Grades (JSON blob) =====

    @GetMapping("/{id}/grades")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getGroupGrades(@PathVariable Long id) {
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        return new ResponseEntity<>(groupOpt.get().getGradesJson(), HttpStatus.OK);
    }

    /**
     * Replaces the entire group grades blob.
     * Body example: [ {"assignment":"HW1","score":95,"course":"CSA"}, ... ]
     */
    @PutMapping("/{id}/grades")
    @Transactional
    public ResponseEntity<?> putGroupGrades(@PathVariable Long id,
                                        @RequestBody List<Map<String, Object>> grades) {
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        
        for (Map<String, Object> grade : grades) {
            grade.put("type", "GROUP");
        }

        Groups group = groupOpt.get();
        group.setGradesJson(grades != null ? grades : new ArrayList<>());
        groupsRepository.save(group);

        return new ResponseEntity<>(group.getGradesJson(), HttpStatus.OK);
    }

    @PostMapping("/{id}/grades")
    @Transactional
    public ResponseEntity<?> addGroupGrade(@PathVariable Long id,
                                        @RequestBody Map<String, Object> gradeEntry) {
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        Groups group = groupOpt.get();
        if (group.getGradesJson() == null) group.setGradesJson(new ArrayList<>());

        // --- Build a canonical group-grade entry (so group + persons get same schema) ---
        Map<String, Object> canonical = new LinkedHashMap<>(gradeEntry);

        // REQUIRED: you should include assignmentId in request, because upsert logic depends on it
        // canonical.get("assignmentId") should exist

        canonical.put("type", "GROUP");
        canonical.put("origin", "GROUP");
        canonical.put("groupId", group.getId());
        canonical.put("groupName", group.getName());
        canonical.put("period", group.getPeriod());
        canonical.put("course", group.getCourse());

        // --- 1) Save into GROUP's gradesJson (use upsert to avoid duplicates) ---
        upsertGradeEntry(group.getGradesJson(), canonical);
        groupsRepository.save(group);

        // --- 2) Propagate into each MEMBER's Person.gradesJson ---
        List<Object[]> memberRows = groupsRepository.findGroupMembersRaw(group.getId());

        List<Long> memberIds = new ArrayList<>();
        for (Object[] row : memberRows) {
            memberIds.add(((Number) row[0]).longValue());
        }

        List<Person> members = personRepository.findAllById(memberIds);

        for (Person p : members) {
            List<Map<String, Object>> grades =
                (p.getGradesJson() == null) ? new ArrayList<>() : new ArrayList<>(p.getGradesJson());

            Map<String, Object> perPerson = new LinkedHashMap<>(canonical);
            upsertGradeEntry(grades, perPerson);

            // IMPORTANT: setter triggers Hibernate dirty checking
            p.setGradesJson(grades);
        }

        personRepository.saveAll(members);


        // Return something useful
        return new ResponseEntity<>(
            Map.of(
                "groupId", group.getId(),
                "updatedMembers", members.size(),
                "groupGrades", group.getGradesJson()
            ),
            HttpStatus.OK
        );
    }


    /**
     * Clears the group grades blob.
     */
    @DeleteMapping("/{id}/grades")
    @Transactional
    public ResponseEntity<?> clearGroupGrades(@PathVariable Long id) {
        Optional<Groups> groupOpt = groupsRepository.findById(id);
        if (groupOpt.isEmpty()) return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        Groups group = groupOpt.get();
        group.setGradesJson(new ArrayList<>());
        groupsRepository.save(group);

        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
    private static String makeGroupGradeKey(Long groupId, String assignmentId) {
        return "GROUP:" + groupId + ":" + assignmentId;
    }

    private static void upsertGrade(List<Map<String, Object>> grades, Map<String, Object> newEntry) {
        String key = (String) newEntry.get("groupGradeKey");
        if (key == null) return;

        for (int i = 0; i < grades.size(); i++) {
            Object existingKey = grades.get(i).get("groupGradeKey");
            if (key.equals(existingKey)) {
                grades.set(i, newEntry); // replace
                return;
            }
        }
        grades.add(newEntry); // insert if not found
    }

    private static void removeGroupGrade(List<Map<String, Object>> grades, Long groupId, String assignmentId) {
        String key = makeGroupGradeKey(groupId, assignmentId);
        grades.removeIf(g -> key.equals(g.get("groupGradeKey")));
    }

    private static void removeAllGroupGradesForGroup(List<Map<String, Object>> grades, Long groupId) {
        String prefix = "GROUP:" + groupId + ":";
        grades.removeIf(g -> {
            Object k = g.get("groupGradeKey");
            return (k instanceof String) && ((String) k).startsWith(prefix);
        });
    }

    private void upsertGradeEntry(List<Map<String, Object>> grades, Map<String, Object> incoming) {
        if (grades == null) return;

        String assignmentId = incoming.get("assignmentId") != null ? incoming.get("assignmentId").toString() : null;
        String groupId = incoming.get("groupId") != null ? incoming.get("groupId").toString() : null;

        if (assignmentId == null) {
            // if you want, throw instead:
            // throw new IllegalArgumentException("assignmentId is required");
            grades.add(incoming);
            return;
        }

        // Find existing entry with same assignmentId + groupId (for GROUP-origin grades)
        for (int i = 0; i < grades.size(); i++) {
            Map<String, Object> existing = grades.get(i);

            String existingAssignmentId = existing.get("assignmentId") != null ? existing.get("assignmentId").toString() : null;
            String existingGroupId = existing.get("groupId") != null ? existing.get("groupId").toString() : null;

            if (assignmentId.equals(existingAssignmentId) &&
                ((groupId == null && existingGroupId == null) || (groupId != null && groupId.equals(existingGroupId)))) {
                grades.set(i, incoming); // replace
                return;
            }
        }

        grades.add(incoming); // insert
    }


}