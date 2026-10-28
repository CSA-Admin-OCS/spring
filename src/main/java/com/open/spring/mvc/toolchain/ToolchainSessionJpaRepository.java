package com.open.spring.mvc.toolchain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ToolchainSessionJpaRepository extends JpaRepository<ToolchainSessionEntity, String> {
    Optional<ToolchainSessionEntity> findByPairingCodeHash(String pairingCodeHash);

    /**
     * Marks a pairing code used, but only if it is unused and unexpired.
     * A single UPDATE is atomic, so when two agents race with the same code
     * exactly one of them gets 1 back.
     *
     * @return 1 when this call consumed the code, 0 otherwise
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ToolchainSessionEntity s SET s.pairingUsedAt = :now"
            + " WHERE s.pairingCodeHash = :hash AND s.pairingUsedAt IS NULL AND s.pairingExpiresAt > :now")
    int consumePairingCode(@Param("hash") String pairingCodeHash, @Param("now") long now);
}
