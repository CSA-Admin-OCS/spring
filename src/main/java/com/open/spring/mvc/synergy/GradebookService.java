package com.open.spring.mvc.synergy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.hibernate.Hibernate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.assignments.Assignment;
import com.open.spring.mvc.assignments.AssignmentAiRegradeService;
import com.open.spring.mvc.assignments.AssignmentRubricService;
import com.open.spring.mvc.assignments.AssignmentSubmission;
import com.open.spring.mvc.assignments.AssignmentSubmissionHistoryService;
import com.open.spring.mvc.assignments.AssignmentSubmissionJPA;
import com.open.spring.mvc.assignments.SubmissionGradingState;
import com.open.spring.mvc.groups.CourseGroupProperties;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.groups.Submitter;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Builds the gradebook card view from submission grades (AI or teacher): one summary row per
 * student in a course, and a per-student card with every submission. A student's grades include
 * the submissions of any group they belong to. Only the current submission per assignment counts;
 * earlier versions are reachable through its history.
 */
@Service
public class GradebookService {

    private static final int RECENT_CHANGES = 5;

    /** Pseudo-course for students who are in none of the class groups; shown as the last tab. */
    public static final String NO_CLASS = "NONE";

    /** Gradebook tab order; class groups not listed here follow in config order. */
    private static final List<String> TAB_ORDER = List.of("CSSE", "CSP", "CSA", "CSH");

    private final GroupsJpaRepository groupsRepository;
    private final AssignmentSubmissionJPA submissionRepository;
    private final AssignmentAiRegradeService aiRegradeService;
    private final CourseGroupProperties courseGroups;
    private final JdbcTemplate jdbcTemplate;
    private final PersonJpaRepository personRepository;

    public GradebookService(GroupsJpaRepository groupsRepository, AssignmentSubmissionJPA submissionRepository,
            AssignmentAiRegradeService aiRegradeService, CourseGroupProperties courseGroups, JdbcTemplate jdbcTemplate,
            PersonJpaRepository personRepository) {
        this.groupsRepository = groupsRepository;
        this.submissionRepository = submissionRepository;
        this.aiRegradeService = aiRegradeService;
        this.courseGroups = courseGroups;
        this.jdbcTemplate = jdbcTemplate;
        this.personRepository = personRepository;
    }

    /**
     * One card in the course grid. {@code overallPct} is null until something is graded; {@code role} is
     * "Admin" or "Teacher" for staff and null for students.
     */
    public record StudentRow(Long id, String uid, String name, String role, Double overallPct, long gradedCount,
            long submittedCount, long pendingAiCount) {
    }

    /**
     * One submission on a student card, with what was submitted so the card can show it.
     * {@code earlierVersions} counts the versions it replaced (see AssignmentSubmissionHistoryService).
     */
    public record SubmissionEntry(Long submissionId, Long assignmentId, String assignmentName, Double points,
            Double grade, String feedback, Integer qualityScore, Boolean isLate, String status,
            LocalDateTime gradedAt, Long etaSeconds, String submitterName, Map<String, Object> content,
            String comment, String aiSummary, SubmissionGradingState grading, int earlierVersions) {
    }

    public record StudentCard(Long id, String uid, String name, Double overallPct, List<SubmissionEntry> recentChanges,
            List<SubmissionEntry> submissions) {
    }

    /** Class groups in gradebook tab order. */
    public List<String> courses() {
        List<String> ordered = new ArrayList<>(courseGroups.getClassGroups());
        ordered.sort(Comparator.comparingInt(name -> TAB_ORDER.contains(name) ? TAB_ORDER.indexOf(name) : TAB_ORDER.size()));
        return ordered;
    }

    /**
     * Students in {@code course} (every class group when blank, or no class group for {@link #NO_CLASS})
     * whose name contains {@code query}, sorted by name, with their grade totals.
     */
    public List<StudentRow> roster(String course, String query) {
        String requested = course == null ? "" : course.trim().toUpperCase(Locale.ROOT);
        List<String> names = requested.isEmpty() || requested.equals(NO_CLASS) ? courses() : List.of(requested);
        Map<Long, Object[]> members = new LinkedHashMap<>();
        for (String name : names) {
            if (!courses().contains(name)) {
                throw new IllegalArgumentException("Unknown course " + name);
            }
            groupsRepository.findByName(name).ifPresent(group ->
                    groupsRepository.findGroupMembersRaw(group.getId())
                            .forEach(row -> members.putIfAbsent(((Number) row[0]).longValue(), row)));
        }
        Set<Long> admins = idsWithRole("ROLE_ADMIN");
        Set<Long> teachers = idsWithRole("ROLE_TEACHER");
        Map<Long, Object[]> people = requested.equals(NO_CLASS) ? withoutClass(members.keySet(), teachers, admins) : members;
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        Map<Long, Totals> totals = totalsByPerson();
        List<StudentRow> rows = new ArrayList<>();
        for (Object[] person : people.values()) {
            String name = person[2] == null ? "" : person[2].toString();
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Long id = ((Number) person[0]).longValue();
            Totals t = totals.getOrDefault(id, new Totals());
            String role = admins.contains(id) ? "Admin" : teachers.contains(id) ? "Teacher" : null;
            rows.add(new StudentRow(id, (String) person[1], name, role, t.percent(), t.graded, t.submitted, t.pendingAi));
        }
        rows.sort(Comparator.comparing(row -> row.name().toLowerCase(Locale.ROOT)));
        return rows;
    }

    private Set<Long> idsWithRole(String role) {
        Set<Long> ids = new HashSet<>();
        personRepository.findPeopleWithRole(role).forEach(person -> ids.add(person.getId()));
        return ids;
    }

    /** Everyone outside {@code inClass} as {id, uid, name} rows, skipping teachers unless they are also admins. */
    private Map<Long, Object[]> withoutClass(Set<Long> inClass, Set<Long> teachers, Set<Long> admins) {
        Set<Long> skip = new HashSet<>(inClass);
        teachers.stream().filter(id -> !admins.contains(id)).forEach(skip::add);
        Map<Long, Object[]> people = new LinkedHashMap<>();
        jdbcTemplate.queryForList("SELECT id, uid, name FROM person").forEach(row -> {
            long id = ((Number) row.get("id")).longValue();
            if (!skip.contains(id)) {
                people.put(id, new Object[] {id, row.get("uid"), row.get("name")});
            }
        });
        return people;
    }

    /** Grade totals per person over current submissions, crediting group submissions to each member. */
    private Map<Long, Totals> totalsByPerson() {
        Map<Long, List<Long>> membersByGroup = new HashMap<>();
        jdbcTemplate.queryForList("SELECT group_id, person_id FROM group_members").forEach(row ->
                membersByGroup.computeIfAbsent(((Number) row.get("group_id")).longValue(), id -> new ArrayList<>())
                        .add(((Number) row.get("person_id")).longValue()));
        Map<Long, Totals> totals = new HashMap<>();
        jdbcTemplate.queryForList("SELECT s.submitter_id, s.grade, s.needs_ai_regrade, a.points "
                + "FROM assignment_submission s LEFT JOIN assignment a ON a.id = s.assignment_id "
                + "WHERE " + AssignmentSubmissionHistoryService.currentRowSql("s")).forEach(row -> {
                    Object submitter = row.get("submitter_id");
                    if (submitter == null) {
                        return;
                    }
                    long submitterId = ((Number) submitter).longValue();
                    List<Long> credited = membersByGroup.getOrDefault(submitterId, List.of(submitterId));
                    for (Long personId : credited) {
                        totals.computeIfAbsent(personId, id -> new Totals()).add(
                                (Number) row.get("grade"), (Number) row.get("points"), row.get("needs_ai_regrade"));
                    }
                });
        return totals;
    }

    /**
     * The current submission per assignment by the person or one of their groups, newest first,
     * with queue ETAs and how many earlier versions each has.
     */
    @Transactional(readOnly = true)
    public StudentCard card(Person person) {
        Map<Long, Long> etaBySubmission = new HashMap<>();
        aiRegradeService.queueSnapshot().entries()
                .forEach(entry -> etaBySubmission.put(entry.submissionId(), entry.etaSeconds()));
        List<AssignmentSubmission> all = Stream.concat(
                submissionRepository.findBySubmitterId(person.getId()).stream(),
                groupsRepository.findGroupsByPersonId(person.getId()).stream()
                        .map(Groups::getId)
                        .flatMap(groupId -> submissionRepository.findBySubmitterId(groupId).stream()))
                .toList();
        Totals totals = new Totals();
        List<SubmissionEntry> entries = new ArrayList<>();
        AssignmentSubmissionHistoryService.latestOnly(all).forEach(submission -> {
            Assignment assignment = submission.getAssignment();
            Double points = assignment == null ? null : assignment.getPoints();
            totals.add(submission.getGrade(), points, submission.getNeedsAiRegrade());
            entries.add(toEntry(submission, assignment, points, etaBySubmission.get(submission.getId()),
                    AssignmentSubmissionHistoryService.earlierVersionCount(submission, all)));
        });
        entries.sort(Comparator.comparing(SubmissionEntry::submissionId).reversed());
        List<SubmissionEntry> recent = entries.stream()
                .filter(entry -> entry.gradedAt() != null)
                .sorted(Comparator.comparing(SubmissionEntry::gradedAt).reversed())
                .limit(RECENT_CHANGES)
                .toList();
        return new StudentCard(person.getId(), person.getUid(), person.getName(), totals.percent(), recent, entries);
    }

    private static SubmissionEntry toEntry(AssignmentSubmission submission, Assignment assignment, Double points, Long eta,
            int earlierVersions) {
        String status = Boolean.TRUE.equals(submission.getNeedsAiRegrade()) ? "pending_ai"
                : submission.getGrade() == null ? "ungraded" : "graded";
        return new SubmissionEntry(submission.getId(), assignment == null ? null : assignment.getId(),
                assignment == null ? "(deleted assignment)" : assignment.getName(), points, submission.getGrade(),
                submission.getFeedback(), submission.getQualityScore(), submission.getIsLate(), status,
                submission.getGradedAt(), eta, submitterName(submission.getSubmitter()), submission.getContent(),
                submission.getComment(), submission.getAiSummary(), SubmissionGradingState.of(submission), earlierVersions);
    }

    private static String submitterName(Submitter submitter) {
        Object unproxied = Hibernate.unproxy(submitter);
        if (unproxied instanceof Person person) {
            return person.getName();
        }
        return unproxied instanceof Groups group ? group.getName() : null;
    }

    /** Running totals; the percentage only counts graded submissions. */
    static final class Totals {
        double earned;
        double possible;
        long graded;
        long submitted;
        long pendingAi;

        void add(Number grade, Number points, Object needsAiRegrade) {
            submitted++;
            if (needsAiRegrade instanceof Boolean flag ? flag : needsAiRegrade instanceof Number n && n.intValue() == 1) {
                pendingAi++;
            }
            if (grade != null) {
                graded++;
                earned += grade.doubleValue();
                possible += AssignmentRubricService.maxScore(points == null ? null : points.doubleValue());
            }
        }

        Double percent() {
            return possible <= 0 ? null : Math.round(earned / possible * 1000.0) / 10.0;
        }
    }
}
