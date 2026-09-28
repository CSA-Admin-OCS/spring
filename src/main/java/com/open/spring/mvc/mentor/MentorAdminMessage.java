package com.open.spring.mvc.mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One message in the private thread between a mentor and the admins. Every mentor has
 * exactly one such thread, keyed by the mentor's uid; any admin can read and reply.
 * Stored in the database (not the S3 group-chat store) so it works without AWS config.
 * Table is created by ModelInit (ddl-auto=none).
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MentorAdminMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The mentor this thread belongs to, whichever side sent the message.
    @Column(nullable = false)
    private String mentorUid;

    @Column(nullable = false)
    private String senderUid;

    private String senderName;

    // True when an admin wrote it, so the UI can tell the two sides apart.
    private boolean fromAdmin;

    @Column(nullable = false, length = 2000)
    private String message;

    // ISO-8601 instant, same format as group chat's GroupChatMessage.date.
    private String createdAt;
}
