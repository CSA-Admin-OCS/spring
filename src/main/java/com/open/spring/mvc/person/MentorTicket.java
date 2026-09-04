package com.open.spring.mvc.person;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Raised automatically whenever a mentor signs up (see PersonApiController#postPerson).
// Mirrors ResetTicket's shape: an admin resolves it from the person/read portal, either
// approving (which promotes the uid straight to ROLE_MENTOR) or denying (which just closes
// the ticket, leaving the account in ROLE_PENDING for the admin to handle by hand).
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class MentorTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    private String uid;

    // Snapshot of the person's name at signup time, so the ticket stays readable even if
    // the account is later renamed or removed.
    private String name;

    // The email the mentor signed up with.
    private String email;

    // True only if that email was verified via Google Sign-In AND matched the trusted-domain
    // whitelist -- false for the "Skip, verify later" path or an unrecognized domain.
    private boolean emailVerified = false;

    private boolean resolved = false;

    // Only meaningful once resolved=true.
    private boolean approved = false;

    private String createdAt;

    private String resolvedAt;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MentorTicket(String uid, String name, String email, boolean emailVerified) {
        this.uid = uid;
        this.name = name;
        this.email = email;
        this.emailVerified = emailVerified;
        this.createdAt = LocalDateTime.now().format(FORMATTER);
    }

    public void markResolved(boolean approved) {
        this.resolved = true;
        this.approved = approved;
        this.resolvedAt = LocalDateTime.now().format(FORMATTER);
    }
}
