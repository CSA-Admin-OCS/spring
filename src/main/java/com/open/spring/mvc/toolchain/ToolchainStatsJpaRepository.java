package com.open.spring.mvc.toolchain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.stats.Stats;

/**
 * Repository for performing CS Pathway specific queries against the shared {@link Stats} table.
 * This repository is isolated to prevent modifications to the core stats module.
 */
public interface ToolchainStatsJpaRepository extends JpaRepository<Stats, Long> {
    /** Retrieves all stats entries for a specific module. */
    List<Stats> findAllByModule(String module);

    /** Retrieves all stats entries for a specific user within a module. */
    List<Stats> findAllByUsernameAndModule(String username, String module);

    /** Retrieves a specific submodule stat for a user. */
    Optional<Stats> findByUsernameAndModuleAndSubmodule(String username, String module, int submodule);
}
