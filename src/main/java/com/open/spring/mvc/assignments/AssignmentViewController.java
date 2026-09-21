package com.open.spring.mvc.assignments;

import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.S3uploads.FileHandler;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

@Controller
@RequestMapping("/mvc/assignments")
public class AssignmentViewController {

    @Autowired
    private AssignmentJpaRepository assignmentRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private AssignmentSubmissionJPA submissionRepo;


    @Autowired
    private FileHandler fileHandler;

    @GetMapping("/tracker")
    public String assignmentTracker(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        
        if (user == null || (!user.hasRoleWithName("ROLE_TEACHER") && !user.hasRoleWithName("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a teacher or admin to access the assignment tracker"
            );
        }

        return "assignments/assignment_tracker";
    }

    @GetMapping("/tracker-v2")
    public String assignmentTrackerV2(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication required");
        }

        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        if (user == null || (!user.hasRoleWithName("ROLE_TEACHER") && !user.hasRoleWithName("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a teacher or admin to access the assignment tracker"
            );
        }

        model.addAttribute("assignments", assignmentRepository.findAll());
        return "assignments/assignment_tracker_v2";
    }

    @PostMapping("/tracker-v2/create")
    public String createAssignmentV2(
            @RequestParam(required = false) Long customId,
            @RequestParam String name,
            @RequestParam String type,
            @RequestParam(required = false, defaultValue = "") String description,
            @RequestParam Double points,
            @RequestParam String dueDate,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication required");
        }

        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        if (user == null || (!user.hasRoleWithName("ROLE_TEACHER") && !user.hasRoleWithName("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a teacher or admin to create assignments"
            );
        }

        String normalizedDueDate = dueDate;
        if (dueDate != null && dueDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            String[] parts = dueDate.split("-");
            normalizedDueDate = parts[1] + "/" + parts[2] + "/" + parts[0];
        }

        Assignment assignment = new Assignment(name, type, description, points, normalizedDueDate);
        // Assignment.id is @GeneratedValue; setting it manually causes merge/stale-state issues.
        // Keep customId as a tolerated input for backward-compatible form posts, but ignore it.

        assignmentRepository.save(assignment);
        return "redirect:/mvc/assignments/tracker-v2";
    }


    @GetMapping("/tracker-v2/file/{id}")
    public ResponseEntity<?> downloadAssignmentResourceFile(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        if (userDetails == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication required");
        }

        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        if (user == null || (!user.hasRoleWithName("ROLE_TEACHER") && !user.hasRoleWithName("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a teacher or admin to access assignment files"
            );
        }

        Assignment assignment = assignmentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));

        if (!"file".equalsIgnoreCase(assignment.getResourceType())) {
            return ResponseEntity.badRequest().body("Assignment resource is not a file");
        }

        String storagePath = assignment.getResourceStoragePath();
        if (storagePath == null || storagePath.isBlank() || !storagePath.contains("/")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Assignment file path missing or invalid");
        }

        int slash = storagePath.indexOf('/');
        String ownerUid = storagePath.substring(0, slash);
        String key = storagePath.substring(slash + 1);
        String base64Data = fileHandler.decodeFile(ownerUid, key);
        if (base64Data == null || base64Data.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Assignment file content not found");
        }

        byte[] bytes = Base64.getDecoder().decode(base64Data);
        String filename = assignment.getResourceFilename() == null || assignment.getResourceFilename().isBlank()
            ? "assignment-resource.bin"
            : assignment.getResourceFilename();

        ByteArrayResource resource = new ByteArrayResource(bytes);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(bytes.length)
            .body(resource);
    }

    @GetMapping
    public String listAssignments(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        
        if (user == null) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a logged-in user to view assignments"
            );
        }

        List<Assignment> assignments = assignmentRepository.findAll();
        model.addAttribute("assignments", assignments);

        // If user is a student, show student view
        if (user.hasRoleWithName("ROLE_STUDENT")) {
            return "assignments/student_assignments";
        } 
        // If user is a teacher or admin, show all assignments
        else if (user.hasRoleWithName("ROLE_TEACHER") || user.hasRoleWithName("ROLE_ADMIN")) {
            return "assignments/teacher_assignments";
        }

        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "You must be a student, teacher, or admin to view assignments"
        );
    }

    @GetMapping("/{id}")
    public String viewAssignmentDetails(
            @PathVariable Long id, 
            Model model, 
            @AuthenticationPrincipal UserDetails userDetails) {
        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        
        if (user == null) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a logged-in user to view assignment details"
            );
        }

        Assignment assignment = assignmentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Assignment not found"
            ));
        
        model.addAttribute("assignment", assignment);

        // If user is a student, show student view
        if (user.hasRoleWithName("ROLE_STUDENT")) {
            return "assignments/student_assignment_details";
        } 
        // If user is a teacher or admin, show detailed view
        else if (user.hasRoleWithName("ROLE_TEACHER") || user.hasRoleWithName("ROLE_ADMIN")) {
            return "assignments/teacher_assignment_details";
        }

        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "You must be a student, teacher, or admin to view assignment details"
        );
    }

    @GetMapping("/{id}/submissions")
    public String viewAssignmentSubmissions(
            @PathVariable Long id, 
            Model model, 
            @AuthenticationPrincipal UserDetails userDetails) {
        
        String uid = userDetails.getUsername();
        Person user = personRepository.findByUid(uid);
        
        if (user == null || (!user.hasRoleWithName("ROLE_TEACHER") && !user.hasRoleWithName("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN, "You must be a teacher or admin to view assignment submissions"
            );
        }

        Assignment assignment = assignmentRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Assignment not found"
            ));

        List<AssignmentSubmission> submissions = submissionRepo.findByAssignmentId(id);
        model.addAttribute("assignment", assignment);
        model.addAttribute("submissions", submissions);
        return "assignments/assignment_submissions";
    }

    @GetMapping("/queue-management")
    public String queueManagement(){
        return "assignments/queue_management";
    }

    @GetMapping("/read")
    public String read(Model model) {
        List<Assignment> assignments = assignmentRepository.findAll();
        model.addAttribute("list", assignments);
        return "assignments/read";
    }
}