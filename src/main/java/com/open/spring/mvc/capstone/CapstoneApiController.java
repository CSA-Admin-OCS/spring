package com.open.spring.mvc.capstone;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.open.spring.mvc.groups.GroupChatMessage;
import com.open.spring.mvc.groups.GroupChatService;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Capstone projects and their mentors.
 *
 * Project rows mirror the Jekyll capstone posts and are kept in step by
 * scripts/sync_capstones.py hitting POST /sync. Mentor attachment is the part that
 * only exists here -- it is what /mentor reads to decide which projects to show.
 */
@RestController
@RequestMapping("/api/capstones")
public class CapstoneApiController {

    @Autowired
    private CapstoneProjectJpaRepository capstoneRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private CapstoneSyncService capstoneSyncService;

    @Autowired
    private CapstoneApplicationJpaRepository applicationRepository;

    // Reuses the group chat's S3-backed store rather than standing up a second one.
    // That store is keyed by an arbitrary string, so capstone threads live under a
    // "capstone:<slug>" key alongside the group ones without colliding.
    @Autowired
    private GroupChatService groupChatService;

    private String chatKey(CapstoneProject project) {
        return "capstone:" + project.getSlug();
    }

    /** A mentor on the project, or staff. Mirrors the group chat rule. */
    private boolean mayAccess(CapstoneProject project, Authentication authentication) {
        if (hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")) {
            return true;
        }
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            return false;
        }
        String uid = ((UserDetails) authentication.getPrincipal()).getUsername();
        return capstoneRepository.findByMentorUid(uid).stream()
                .anyMatch(p -> p.getId().equals(project.getId()));
    }

    private boolean hasAnyAuthority(Authentication authentication, String... authorities) {
        if (authentication == null) {
            return false;
        }
        for (String authority : authorities) {
            boolean match = authentication.getAuthorities().stream()
                    .anyMatch(granted -> authority.equals(granted.getAuthority()));
            if (match) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> toDto(CapstoneProject project) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", project.getId());
        dto.put("slug", project.getSlug());
        dto.put("title", project.getTitle());
        dto.put("description", project.getDescription());
        dto.put("url", project.getUrl());
        return dto;
    }

    private Map<String, Object> mentorRow(Object[] row) {
        Map<String, Object> mentor = new LinkedHashMap<>();
        mentor.put("id", row[0]);
        mentor.put("uid", row[1]);
        mentor.put("name", row[2]);
        mentor.put("email", row[3]);
        return mentor;
    }

    /** Every capstone project. Deliberately unscoped -- this is public project info. */
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CapstoneProject project : capstoneRepository.findAllByOrderByTitleAsc()) {
            out.add(toDto(project));
        }
        return new ResponseEntity<>(out, HttpStatus.OK);
    }

    /** The caller's own assigned projects -- what /mentor renders. */
    @GetMapping("/mine")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getMine(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        String uid = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CapstoneProject project : capstoneRepository.findByMentorUid(uid)) {
            out.add(toDto(project));
        }
        return new ResponseEntity<>(out, HttpStatus.OK);
    }

    @GetMapping("/mentor/{personId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getByMentor(@PathVariable Long personId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CapstoneProject project : capstoneRepository.findByMentorId(personId)) {
            out.add(toDto(project));
        }
        return new ResponseEntity<>(out, HttpStatus.OK);
    }

    @GetMapping("/{id}/mentors")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getMentors(@PathVariable Long id) {
        if (capstoneRepository.findById(id).isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : capstoneRepository.findMentorsRaw(id)) {
            out.add(mentorRow(row));
        }
        return new ResponseEntity<>(out, HttpStatus.OK);
    }

    @PostMapping("/{id}/mentors/{personId}")
    @Transactional
    public ResponseEntity<Object> addMentor(@PathVariable Long id, @PathVariable Long personId,
                                            Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        Optional<CapstoneProject> projectOpt = capstoneRepository.findById(id);
        Optional<Person> personOpt = personRepository.findById(personId);
        if (projectOpt.isEmpty() || personOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        CapstoneProject project = projectOpt.get();
        project.addMentor(personOpt.get());
        capstoneRepository.save(project);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @DeleteMapping("/{id}/mentors/{personId}")
    @Transactional
    public ResponseEntity<Object> removeMentor(@PathVariable Long id, @PathVariable Long personId,
                                               Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        Optional<CapstoneProject> projectOpt = capstoneRepository.findById(id);
        Optional<Person> personOpt = personRepository.findById(personId);
        if (projectOpt.isEmpty() || personOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        CapstoneProject project = projectOpt.get();
        project.removeMentor(personOpt.get());
        capstoneRepository.save(project);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    private Map<String, Object> applicationDto(CapstoneApplication application) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", application.getId());
        dto.put("capstoneId", application.getCapstoneProject().getId());
        dto.put("capstoneTitle", application.getCapstoneProject().getTitle());
        dto.put("capstoneUrl", application.getCapstoneProject().getUrl());
        dto.put("appliedAt", application.getAppliedAt());
        dto.put("resolved", application.isResolved());
        dto.put("approved", application.isApproved());
        return dto;
    }

    /**
     * A mentor applies to be attached to one project, raised from the Mentor Portal
     * (/projects). Requires ROLE_MENTOR -- becoming a mentor and being attached to a
     * specific project are two separate approvals, this is only the second one.
     * Idempotent: repeat calls while a request is still pending, or once already a
     * mentor on the project, are both reported rather than creating duplicates.
     */
    @PostMapping("/{id}/apply")
    @Transactional
    public ResponseEntity<Object> apply(@PathVariable Long id, Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_MENTOR")) {
            return new ResponseEntity<>(
                    "Only approved mentors can apply to a project. Sign up as a mentor and wait for admin approval first.",
                    HttpStatus.FORBIDDEN);
        }
        String uid = ((UserDetails) authentication.getPrincipal()).getUsername();
        Optional<CapstoneProject> projectOpt = capstoneRepository.findById(id);
        if (projectOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        CapstoneProject project = projectOpt.get();
        Person person = personRepository.findByUid(uid);
        if (person == null) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }

        boolean alreadyMentor = project.getMentors().stream().anyMatch(m -> m.getUid().equals(uid));
        if (alreadyMentor) {
            return new ResponseEntity<>("You're already a mentor on this project.", HttpStatus.CONFLICT);
        }
        Optional<CapstoneApplication> existing =
                applicationRepository.findByCapstoneProject_IdAndPersonUidAndResolvedFalse(id, uid);
        if (existing.isPresent()) {
            return new ResponseEntity<>(applicationDto(existing.get()), HttpStatus.OK);
        }

        CapstoneApplication application = new CapstoneApplication(project, person);
        applicationRepository.save(application);
        return new ResponseEntity<>(applicationDto(application), HttpStatus.CREATED);
    }

    /** The caller's own project applications (pending, approved, or denied). */
    @GetMapping("/applications/mine")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> myApplications(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserDetails)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        String uid = ((UserDetails) authentication.getPrincipal()).getUsername();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CapstoneApplication application : applicationRepository.findByPersonUidOrderByIdDesc(uid)) {
            out.add(applicationDto(application));
        }
        return new ResponseEntity<>(out, HttpStatus.OK);
    }

    /** Admin/teacher approves a project application: attaches the mentor directly. */
    @PostMapping("/applications/{id}/approve")
    @Transactional
    public ResponseEntity<Object> approveApplication(@PathVariable Long id, Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        Optional<CapstoneApplication> applicationOpt = applicationRepository.findById(id);
        if (applicationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        CapstoneApplication application = applicationOpt.get();
        if (application.isResolved()) {
            return new ResponseEntity<>(HttpStatus.OK);
        }
        CapstoneProject project = application.getCapstoneProject();
        project.addMentor(application.getPerson());
        capstoneRepository.save(project);
        application.markResolved(true);
        applicationRepository.save(application);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    /** Admin/teacher denies a project application: closes it without touching mentors. */
    @PostMapping("/applications/{id}/deny")
    @Transactional
    public ResponseEntity<Object> denyApplication(@PathVariable Long id, Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_ADMIN", "ROLE_TEACHER")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        Optional<CapstoneApplication> applicationOpt = applicationRepository.findById(id);
        if (applicationOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        CapstoneApplication application = applicationOpt.get();
        if (!application.isResolved()) {
            application.markResolved(false);
            applicationRepository.save(application);
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    /**
     * Force an immediate pull of /capstone/projects.json.
     *
     * The same sync runs automatically at startup and every 30 minutes
     * (CapstoneSyncService); this is the "don't wait" button for right after a site
     * rebuild. Admin only.
     */
    @PostMapping("/sync")
    public ResponseEntity<Object> sync(Authentication authentication) {
        if (!hasAnyAuthority(authentication, "ROLE_ADMIN")) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        try {
            CapstoneSyncService.SyncResult result = capstoneSyncService.sync();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("created", result.created);
            body.put("updated", result.updated);
            body.put("total", result.total);
            return new ResponseEntity<>(body, HttpStatus.OK);
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            return new ResponseEntity<>(body, HttpStatus.BAD_GATEWAY);
        }
    }

    @GetMapping("/{id}/messages")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> getMessages(@PathVariable Long id, Authentication authentication) {
        Optional<CapstoneProject> projectOpt = capstoneRepository.findById(id);
        if (projectOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        if (!mayAccess(projectOpt.get(), authentication)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        return new ResponseEntity<>(groupChatService.getMessages(chatKey(projectOpt.get())), HttpStatus.OK);
    }

    @PostMapping("/{id}/messages")
    @Transactional(readOnly = true)
    public ResponseEntity<Object> postMessage(@PathVariable Long id,
                                              @org.springframework.web.bind.annotation.RequestBody GroupChatMessage message,
                                              Authentication authentication) {
        Optional<CapstoneProject> projectOpt = capstoneRepository.findById(id);
        if (projectOpt.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        if (!mayAccess(projectOpt.get(), authentication)) {
            return new ResponseEntity<>(HttpStatus.FORBIDDEN);
        }
        if (message == null || message.getName() == null || message.getMessage() == null) {
            return new ResponseEntity<>("name and message are required", HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(
                groupChatService.addMessage(chatKey(projectOpt.get()), message), HttpStatus.OK);
    }
}
