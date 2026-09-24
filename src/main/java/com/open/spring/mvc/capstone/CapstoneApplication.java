package com.open.spring.mvc.capstone;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.open.spring.mvc.person.Person;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A mentor's request to be attached to one capstone project, raised from the Mentor
 * Portal's "Apply" action (/projects). Mirrors MentorTicket's shape and lifecycle:
 * an admin/teacher resolves it from the capstone admin portal (capstone/read.html),
 * either approving (which calls CapstoneProject#addMentor directly) or denying
 * (which just closes the request without touching mentors).
 *
 * Only ever created for an already-approved mentor (ROLE_MENTOR) -- becoming a
 * mentor and being attached to a specific project are two separate approvals.
 */
@Entity
@Data
@NoArgsConstructor
public class CapstoneApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "capstone_id", nullable = false)
    private CapstoneProject capstoneProject;

    @ManyToOne
    @JoinColumn(name = "person_id", nullable = false)
    @JsonIgnore
    private Person person;

    // Denormalized so the admin table and the mentor's own "mine" view stay readable
    // even if the person or project is later renamed/removed.
    private String personUid;
    private String personName;

    private String appliedAt;

    private boolean resolved = false;

    // Only meaningful once resolved = true.
    private boolean approved = false;

    private String resolvedAt;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public CapstoneApplication(CapstoneProject capstoneProject, Person person) {
        this.capstoneProject = capstoneProject;
        this.person = person;
        this.personUid = person.getUid();
        this.personName = person.getName();
        this.appliedAt = LocalDateTime.now().format(FORMATTER);
    }

    public void markResolved(boolean approved) {
        this.resolved = true;
        this.approved = approved;
        this.resolvedAt = LocalDateTime.now().format(FORMATTER);
    }
}
