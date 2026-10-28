package com.open.spring.mvc.toolchain;

import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single "I need help" request raised by a student during a CS Pathway level.
 * Each request is persisted as a unique row to allow teachers to view a history of all requests.
 * 
 * This entity is mapped to the cs_pathway_help_request table.
 * 
 * Timestamps are stored as epoch milliseconds to ensure compatibility between 
 * SQLite (development) and MySQL (production) environments.
 */
@Entity
@Table(name = "cs_pathway_help_request")
@Data
@NoArgsConstructor
public class ToolchainHelpEntity {

    /** Unique identifier for the request (random UUID). */
    @Id
    private String id;

    /** The unique identifier of the student who raised the request. */
    private String uid;

    /** The identifier for the CS Pathway level where the request occurred. */
    private String levelKey;

    /** The specific station or area the student was on; may be null if the level has no stations. */
    private String station;

    /** The content of the student's help message. */
    private String message;

    /** The creation timestamp of the request in epoch milliseconds. */
    private Long createdAt;

    /**
     * Converts the epoch millisecond timestamp to a {@link Date} object.
     * Used primarily for date formatting in the teacher's view.
     *
     * @return The {@link Date} representation of the creation time, or null if createdAt is null.
     */
    public Date getCreatedAtDate() {
        return createdAt == null ? null : new Date(createdAt);
    }
}
