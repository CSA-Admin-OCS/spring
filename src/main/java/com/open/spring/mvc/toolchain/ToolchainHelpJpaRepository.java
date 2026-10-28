package com.open.spring.mvc.toolchain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for managing {@link ToolchainHelpEntity} entities.
 * Provides methods to retrieve help requests by student ID and for the teacher dashboard.
 */
public interface ToolchainHelpJpaRepository extends JpaRepository<ToolchainHelpEntity, String> {
    /** Retrieves the most recent help request for a specific student. */
    Optional<ToolchainHelpEntity> findFirstByUidOrderByCreatedAtDesc(String uid);

    /** Retrieves the 200 most recent help requests, ordered by creation time. */
    List<ToolchainHelpEntity> findTop200ByOrderByCreatedAtDesc();
}
