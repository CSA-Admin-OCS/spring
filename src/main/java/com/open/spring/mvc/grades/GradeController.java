package com.open.spring.mvc.grades;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

import com.open.spring.mvc.person.PersonJpaRepository;

@RestController
@RequestMapping("/api/grades")
public class GradeController {

    @Autowired
    private PersonJpaRepository personRepository;
    // Only reads may use the optional legacy server credential.
    @Value("${gist.token:}")
    private String gistToken;
    @Autowired
    private com.open.spring.mvc.gist.GistConnectionService gistConnectionService;
    @Autowired
    private com.open.spring.mvc.gist.GistRequestGuard gistRequestGuard;
    @Autowired
    private com.open.spring.mvc.gist.GitHubGistClient gitHubGistClient;

    @GetMapping
    public List<Grade> getAllGrades() {
        List<Grade> out = new ArrayList<>();
        for (com.open.spring.mvc.person.Person p : personRepository.findAll()) {
            List<Map<String, Object>> gjs = p.getGradesJson();
            if (gjs == null) continue;
            for (Map<String, Object> m : gjs) {
                Grade g = mapToGrade(m);
                if (g.getUid() == null) g.setUid(p.getUid());
                out.add(g);
            }
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Grade> getGradeById(@PathVariable Long id) {
        for (com.open.spring.mvc.person.Person p : personRepository.findAll()) {
            List<Map<String, Object>> gjs = p.getGradesJson();
            if (gjs == null) continue;
            for (Map<String, Object> m : gjs) {
                Object mid = m.get("id");
                if (mid != null && Long.valueOf(String.valueOf(mid)).equals(id)) {
                    Grade g = mapToGrade(m);
                    if (g.getUid() == null) g.setUid(p.getUid());
                    return ResponseEntity.ok(g);
                }
            }
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/student/{uid}")
    public List<Grade> getGradesByStudent(@PathVariable String uid) {
        com.open.spring.mvc.person.Person p = personRepository.findByUid(uid);
        List<Grade> out = new ArrayList<>();
        if (p == null) return out;
        List<Map<String, Object>> gjs = p.getGradesJson();
        if (gjs == null) return out;
        for (Map<String, Object> m : gjs) {
            Grade g = mapToGrade(m);
            if (g.getUid() == null) g.setUid(uid);
            out.add(g);
        }
        return out;
    }

    @GetMapping("/assignment/{assignment}")
    public List<Grade> getGradesByAssignment(@PathVariable String assignment) {
        List<Grade> out = new ArrayList<>();
        for (com.open.spring.mvc.person.Person p : personRepository.findAll()) {
            List<Map<String, Object>> gjs = p.getGradesJson();
            if (gjs == null) continue;
            for (Map<String, Object> m : gjs) {
                Object asg = m.get("assignment");
                if (assignment.equals(String.valueOf(asg))) {
                    Grade g = mapToGrade(m);
                    if (g.getUid() == null) g.setUid(p.getUid());
                    out.add(g);
                }
            }
        }
        return out;
    }

    @PostMapping
    public ResponseEntity<Grade> createGrade(@AuthenticationPrincipal UserDetails caller, @RequestBody Grade grade) {
        // Students may only file their own submission; scoring and comments are for teachers
        if (!isStaff(caller)) {
            if (caller == null || !caller.getUsername().equals(grade.getUid())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            grade.setScore(null);
            grade.setTeacherComments(null);
        }
        com.open.spring.mvc.person.Person p = personRepository.findByUid(grade.getUid());
        if (p == null) return ResponseEntity.ok(null);
        Map<String, Object> m = new HashMap<>();

        Long newId = generateNextGradeId();
        m.put("id", newId);
        grade.setId(newId);

        m.put("uid", grade.getUid());
        m.put("assignment", grade.getAssignment());
        m.put("score", grade.getScore());
        m.put("teacherComments", grade.getTeacherComments());
        m.put("submission", grade.getSubmission());
        m.put("submittedAt", grade.getSubmittedAt());

        if (p.getGradesJson() == null) {
            p.setGradesJson(new ArrayList<>());
        }
        p.getGradesJson().add(m);
        personRepository.save(p);
        return ResponseEntity.ok(grade);
    }

    private static boolean isStaff(UserDetails caller) {
        return caller != null && caller.getAuthorities().stream().anyMatch(a ->
                "ROLE_TEACHER".equals(a.getAuthority()) || "ROLE_ADMIN".equals(a.getAuthority()));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyAuthority('ROLE_TEACHER','ROLE_ADMIN')")
    public ResponseEntity<Grade> submitGrade(@PathVariable Long id, @RequestBody Grade gradeDetails) {
        for (com.open.spring.mvc.person.Person p : personRepository.findAll()) {
            List<Map<String, Object>> gjs = p.getGradesJson();
            if (gjs == null) continue;
            for (Map<String, Object> m : gjs) {
                Object mid = m.get("id");
                if (mid != null && Long.valueOf(String.valueOf(mid)).equals(id)) {
                    if (gradeDetails.getUid() != null) m.put("uid", gradeDetails.getUid());
                    if (gradeDetails.getAssignment() != null) m.put("assignment", gradeDetails.getAssignment());
                    if (gradeDetails.getScore() != null) m.put("score", gradeDetails.getScore());
                    if (gradeDetails.getTeacherComments() != null) m.put("teacherComments", gradeDetails.getTeacherComments());
                    if (gradeDetails.getSubmission() != null) m.put("submission", gradeDetails.getSubmission());
                    if (gradeDetails.getSubmittedAt() != null) m.put("submittedAt", gradeDetails.getSubmittedAt());
                    personRepository.save(p);
                    Grade g = mapToGrade(m);
                    if (g.getUid() == null) g.setUid(p.getUid());
                    return ResponseEntity.ok(g);
                }
            }
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping(value = "/create-gist", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> createGist(@RequestBody Map<String, Object> body,
            org.springframework.security.core.Authentication authentication,
            jakarta.servlet.http.HttpServletRequest request) {
        gistRequestGuard.validate(request);
        String url = gistConnectionService.create(authentication, body);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Map.of("success", true, "url", url));
    }

    @GetMapping("/read-gist/{id}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Map<String, Object>> readGist(@PathVariable String id) {
        Map<?, ?> data = gitHubGistClient.read(id, gistToken);
        Map<String, Object> files = new LinkedHashMap<>();
        if (data.get("files") instanceof Map<?, ?> rawFiles) {
            for (var entry : rawFiles.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> file)) continue;
                Map<String, Object> slim = new HashMap<>();
                slim.put("content", file.get("content"));
                files.put(name, slim);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("files", files);
        result.put("description", data.get("description"));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_TEACHER','ROLE_ADMIN')")
    public ResponseEntity<Grade> updateGrade(@PathVariable Long id, @RequestBody Grade gradeDetails) {
        for (com.open.spring.mvc.person.Person p : personRepository.findAll()) {
            List<Map<String, Object>> gjs = p.getGradesJson();
            if (gjs == null) continue;
            for (Map<String, Object> m : gjs) {
                Object mid = m.get("id");
                if (mid != null && Long.valueOf(String.valueOf(mid)).equals(id)) {
                    m.put("assignment", gradeDetails.getAssignment());
                    m.put("score", gradeDetails.getScore());
                    m.put("teacherComments", gradeDetails.getTeacherComments());
                    m.put("submission", gradeDetails.getSubmission());
                    m.put("submittedAt", gradeDetails.getSubmittedAt());
                    personRepository.save(p);
                    Grade g = mapToGrade(m);
                    if (g.getUid() == null) g.setUid(p.getUid());
                    return ResponseEntity.ok(g);
                }
            }
        }
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_TEACHER','ROLE_ADMIN')")
    public ResponseEntity<Void> deleteGrade(@PathVariable Long id) {
        for (com.open.spring.mvc.person.Person p : personRepository.findAll()) {
            List<Map<String, Object>> gjs = p.getGradesJson();
            if (gjs == null) continue;
            boolean removed = gjs.removeIf(m -> {
                Object mid = m.get("id");
                return mid != null && Long.valueOf(String.valueOf(mid)).equals(id);
            });
            if (removed) {
                personRepository.save(p);
                return ResponseEntity.ok().build();
            }
        }
        return ResponseEntity.notFound().build();
    }

    private synchronized Long generateNextGradeId() {
        Long maxId = 0L;
        for (com.open.spring.mvc.person.Person p : personRepository.findAll()) {
            List<Map<String, Object>> gjs = p.getGradesJson();
            if (gjs == null) continue;
            for (Map<String, Object> m : gjs) {
                Object mid = m.get("id");
                if (mid != null) {
                    Long id = Long.valueOf(String.valueOf(mid));
                    if (id > maxId) {
                        maxId = id;
                    }
                }
            }
        }
        return maxId + 1;
    }

    private Grade mapToGrade(Map<String, Object> m) {
        if (m == null) return null;
        Object idObj = m.get("id");
        Long id = idObj == null ? null : Long.valueOf(String.valueOf(idObj));
        String uid = (String) m.get("uid");
        String assignment = m.get("assignment") == null ? null : String.valueOf(m.get("assignment"));
        Double score = null;
        Object scoreObj = m.get("score");
        if (scoreObj != null) {
            try {
                score = Double.valueOf(String.valueOf(scoreObj));
            } catch (NumberFormatException e) {
                score = null;
            }
        }
        String teacherComments = m.get("teacherComments") == null ? null : String.valueOf(m.get("teacherComments"));
        Object submission = m.get("submission");
        String submittedAt = m.get("submittedAt") == null ? null : String.valueOf(m.get("submittedAt"));
        Grade g = new Grade(uid, assignment, score, teacherComments, submission, submittedAt);
        g.setId(id);
        return g;
    }
}
