package com.open.spring.mvc.directory;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "directory_accounts")
@Getter
@Setter
@NoArgsConstructor
public class DirectoryAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @NotBlank
    @Email
    @Size(max = 254)
    @Column(nullable = false, length = 254)
    private String email;

    @Size(max = 120)
    @Column(length = 120)
    private String school;

    @Size(max = 40)
    @Column(name = "student_id", length = 40)
    private String studentID;

    @Size(max = 39)
    @Pattern(regexp = "^$|[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*",
             message = "Use letters, numbers, and single internal hyphens")
    @Column(name = "github_username", length = 39)
    private String githubUsername;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType = AccountType.STUDENT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void recordCreationTime() {
        createdAt = LocalDateTime.now(java.time.Clock.systemUTC());
    }
}

