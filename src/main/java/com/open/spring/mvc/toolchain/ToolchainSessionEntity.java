package com.open.spring.mvc.toolchain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One student's paired Toolchain Trail agent: the one-time pairing code the game
 * showed, the agent token the local Python agent received for it, and the station
 * the game currently wants checked. One pairing covers the whole trail; the agent
 * may only report on the station the game asked about. Codes and tokens are stored only as SHA-256
 * hashes, so a copy of the database cannot be used to report progress.
 * Times are epoch milliseconds so the same column type works on SQLite and MySQL.
 * The table is initialized by ToolchainSessionTableInitializer (ddl-auto is none).
 */
@Entity
@Table(name = "toolchain_session")
@Data
@NoArgsConstructor
public class ToolchainSessionEntity {
    public static final String WAITING_FOR_AGENT = "WAITING_FOR_AGENT";
    /** The game asked for a check and the agent has not reported on it yet. */
    public static final String CHECKING = "CHECKING";
    public static final String VERIFIED = "VERIFIED";
    public static final String FAILED = "FAILED";
    /** Never stored: reported for a session whose code expired before an agent paired. */
    public static final String EXPIRED = "EXPIRED";
    /** Never stored: reported once the agent token has expired; the game must pair again. */
    public static final String AGENT_EXPIRED = "AGENT_EXPIRED";

    @Id
    private String id; // UUID

    private String studentUid;
    private String stationId; // the station the game most recently asked the agent to check
    private Long requestedAt; // when the game asked; the agent runs each request once

    private String pairingCodeHash;
    private Long pairingExpiresAt;
    private Long pairingUsedAt; // set once, when an agent exchanges the code

    private String agentTokenHash;
    private Long agentTokenExpiresAt;

    private String status;
    private String agentOs; // what the agent says it runs on: macos, windows, linux, wsl
    private String results; // JSON array of ToolchainStationRules.CheckResult for stationId
    private Long createdAt;
    private Long updatedAt;
}
