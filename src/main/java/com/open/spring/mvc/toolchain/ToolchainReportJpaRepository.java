package com.open.spring.mvc.toolchain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for managing {@link ToolchainReportEntity} entities.
 * Handles pairing code lookups and retrieval of reported setup reports.
 */
public interface ToolchainReportJpaRepository extends JpaRepository<ToolchainReportEntity, String> {
    /** Finds a setup report associated with a specific pairing code. */
    Optional<ToolchainReportEntity> findByPairingCode(String pairingCode);

    /** Checks if a pairing code is currently in use. */
    boolean existsByPairingCode(String pairingCode);

    /** Retrieves all setup reports that have been successfully uploaded. */
    List<ToolchainReportEntity> findAllByReportedAtIsNotNull();
}
