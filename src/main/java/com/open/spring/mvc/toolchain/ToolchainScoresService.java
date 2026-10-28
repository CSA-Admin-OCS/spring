package com.open.spring.mvc.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.stats.Stats;

/**
 * Reads and records CS Pathway level scores.
 * Each (student, level) is one row in the shared stats table:
 * username = person uid, module = "cs-pathway", submodule = level number,
 * grades = percent complete (0-100), finished = reached 100%.
 */
@Service
public class ToolchainScoresService {
    /**
     * DTO for a student's score in one CS Pathway level.
     *
     * @param levelKey the level identifier
     * @param levelName the display name of the level
     * @param percentComplete the completion percentage from 0 through 100
     * @param finished whether the student has completed the level
     */
    public record LevelScoreRecord(
            String levelKey,
            String levelName,
            double percentComplete,
            boolean finished) {
    }

    /**
     * DTO summarizing a student's scores for the teacher dashboard.
     *
     * @param uid the student's unique identifier
     * @param name the student's display name
     * @param levels the scores for each level
     * @param averagePercent the average completion percentage across all levels
     */
    public record StudentRecord(
            String uid,
            String name,
            List<LevelScoreRecord> levels,
            double averagePercent) {
    }

    static final String MODULE = "cs-pathway";
    private static final double MAX_PERCENT = 100.0;

    private final ToolchainStatsJpaRepository statsRepository;
    private final PersonJpaRepository personRepository;

    public ToolchainScoresService(ToolchainStatsJpaRepository statsRepository, PersonJpaRepository personRepository) {
        this.statsRepository = statsRepository;
        this.personRepository = personRepository;
    }

    /** All five levels for one student; levels never played come back as 0%. */
    public List<LevelScoreRecord> getScores(String uid) {
        requireUid(uid);
        return toLevelScores(statsRepository.findAllByUsernameAndModule(uid, MODULE));
    }

    /**
     * Records a student's progress on one level.
     * Progress only moves forward: the game rebuilds its task list from browser
     * storage, so a new device would otherwise report a lower percent and wipe
     * earlier work.
     */
    public LevelScoreRecord recordProgress(String uid, ToolchainEnumLevels level, Double percentComplete) {
        requireUid(uid);
        if (level == null) {
            throw new IllegalArgumentException("Level is required.");
        }
        if (percentComplete == null || percentComplete.isNaN()
                || percentComplete < 0 || percentComplete > MAX_PERCENT) {
            throw new IllegalArgumentException("percentComplete must be a number from 0 to 100.");
        }

        Stats stats = statsRepository.findByUsernameAndModuleAndSubmodule(uid, MODULE, level.getSubmodule())
                .orElseGet(() -> newStats(uid, level));
        double previous = stats.getGrades() == null ? 0.0 : stats.getGrades();
        double next = Math.max(previous, percentComplete);
        stats.setGrades(next);
        stats.setFinished(next >= MAX_PERCENT);
        return toScore(level, statsRepository.save(stats));
    }

    /** Every student with any CS Pathway progress, sorted by name, for the teacher view. */
    public List<StudentRecord> getStudentRows() {
        Map<String, List<Stats>> byStudent = statsRepository.findAllByModule(MODULE).stream()
                .collect(Collectors.groupingBy(Stats::getUsername));

        List<StudentRecord> rows = new ArrayList<>();
        for (Map.Entry<String, List<Stats>> student : byStudent.entrySet()) {
            String uid = student.getKey();
            List<LevelScoreRecord> levels = toLevelScores(student.getValue());
            double average = levels.stream().mapToDouble(LevelScoreRecord::percentComplete).average().orElse(0);
            rows.add(new StudentRecord(uid, displayName(uid), levels, average));
        }
        rows.sort(Comparator.comparing(StudentRecord::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private String displayName(String uid) {
        Person person = personRepository.findByUid(uid);
        if (person == null || person.getName() == null || person.getName().isBlank()) {
            return uid;
        }
        return person.getName();
    }

    private static Stats newStats(String uid, ToolchainEnumLevels level) {
        Stats stats = new Stats();
        stats.setUsername(uid);
        stats.setModule(MODULE);
        stats.setSubmodule(level.getSubmodule());
        stats.setTime(0); // column is NOT NULL; the game does not track time on page
        return stats;
    }

    /** One score per level in play order, filling unplayed levels with 0%. */
    private static List<LevelScoreRecord> toLevelScores(List<Stats> studentStats) {
        Map<Integer, Stats> bySubmodule = studentStats.stream()
                .collect(Collectors.toMap(Stats::getSubmodule, Function.identity(), (first, second) -> first));
        List<LevelScoreRecord> scores = new ArrayList<>();
        for (ToolchainEnumLevels level : ToolchainEnumLevels.values()) {
            scores.add(toScore(level, bySubmodule.get(level.getSubmodule())));
        }
        return scores;
    }

    private static LevelScoreRecord toScore(ToolchainEnumLevels level, Stats stats) {
        double percent = stats == null || stats.getGrades() == null ? 0.0 : stats.getGrades();
        boolean finished = stats != null && Boolean.TRUE.equals(stats.getFinished());
        return new LevelScoreRecord(level.getKey(), level.getDisplayName(), percent, finished);
    }

    private static void requireUid(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("Student uid is required.");
        }
    }
}
