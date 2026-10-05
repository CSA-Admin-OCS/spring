package com.open.spring.mvc.capstone;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;
import com.open.spring.mvc.person.Person;

import lombok.RequiredArgsConstructor;

/**
 * Keeps a capstone's approved mentors in step with the student group linked to it.
 *
 * A mentor only reaches a project's students through that group's chat, so group
 * mentorship follows project mentorship: attaching a mentor to the project (admin
 * approval or the admin "+ mentor" button) also makes them a mentor of the linked
 * group, and detaching them takes it away again. Linking a group to a project that
 * already has mentors gives those mentors access; unlinking removes it.
 *
 * A project with no linked group gets its own ("Capstone: <title>") the first time a
 * mentor is attached, so an approved mentor can always reach the project chat right away;
 * admins add the team's students to that group (or link a different group instead).
 */
@Service
@RequiredArgsConstructor
public class CapstoneGroupLinkService {
    private static final Logger logger = LoggerFactory.getLogger(CapstoneGroupLinkService.class);

    private final CapstoneProjectJpaRepository capstoneRepository;
    private final GroupsJpaRepository groupsRepository;
    private final TransactionTemplate transactionTemplate;

    static final String GROUP_NAME_PREFIX = "Capstone: ";
    private static final int BACKFILL_ATTEMPTS = 5;
    private static final long BACKFILL_RETRY_DELAY_MS = 2_000L;
    private static final int CHAT_GROUP_ATTEMPTS = 4;
    private static final long CHAT_GROUP_RETRY_DELAY_MS = 300L;

    @Transactional
    public void attachMentor(CapstoneProject project, Person mentor) {
        project.addMentor(mentor);
        capstoneRepository.save(project);
        Groups group = ensureGroup(project);
        group.addMentor(mentor);
        groupsRepository.save(group);
    }

    /** The project's linked group, creating and linking "Capstone: <title>" if it has none. */
    @Transactional
    public Groups ensureGroup(CapstoneProject project) {
        Optional<Groups> linked = linkedGroup(project);
        if (linked.isPresent()) {
            return linked.get();
        }
        String name = GROUP_NAME_PREFIX + project.getTitle();
        Groups group = groupsRepository.findByName(name)
                .orElseGet(() -> groupsRepository.save(new Groups(name, "", "", new ArrayList<>())));
        project.setGroupId(group.getId());
        capstoneRepository.save(project);
        logger.info("AUDIT capstone_group_created capstone={} group={}", project.getSlug(), group.getId());
        return group;
    }

    // Projects approved before groups were created automatically have mentors but no chat
    // group; give each one its group (with its mentors). Runs after startup settles, one
    // project per transaction, and never throws: during startup other writers hold the
    // SQLite database, and a failure in a startup listener stops the whole app.
    @Scheduled(initialDelay = 60_000L, fixedDelay = 1_800_000L)
    public void backfillProjectGroups() {
        List<Long> projectIds;
        try {
            projectIds = transactionTemplate.execute(status -> capstoneRepository.findAll().stream()
                    .filter(project -> project.getGroupId() == null && !project.getMentors().isEmpty())
                    .map(CapstoneProject::getId)
                    .toList());
        } catch (RuntimeException e) {
            logger.warn("capstone_group_backfill skipped: {}", e.getMessage());
            return;
        }
        for (Long projectId : projectIds) {
            backfillWithRetry(projectId);
        }
    }

    // SQLite rejects a write when another connection wrote after this transaction began
    // (SQLITE_BUSY_SNAPSHOT); a fresh transaction a moment later normally succeeds.
    private void backfillWithRetry(Long projectId) {
        for (int attempt = 1; attempt <= BACKFILL_ATTEMPTS; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> capstoneRepository.findById(projectId).ifPresent(project -> {
                    Groups group = ensureGroup(project);
                    project.getMentors().forEach(group::addMentor);
                    groupsRepository.save(group);
                }));
                return;
            } catch (RuntimeException e) {
                logger.warn("capstone_group_backfill attempt {}/{} failed for project {}: {}",
                        attempt, BACKFILL_ATTEMPTS, projectId, e.getMessage());
                if (attempt < BACKFILL_ATTEMPTS) {
                    try {
                        Thread.sleep(BACKFILL_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /**
     * The project's chat group id, creating its group on first use (the capstone card's
     * Chat button); empty if there is no such project. Retries SQLITE_BUSY_SNAPSHOT like
     * the backfill, with shorter waits since someone is waiting on the dialog.
     */
    public Optional<Long> chatGroupIdFor(Long projectId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> capstoneRepository.findById(projectId)
                        .map(project -> ensureGroup(project).getId()));
            } catch (CannotAcquireLockException e) {
                if (attempt >= CHAT_GROUP_ATTEMPTS) {
                    throw e;
                }
                logger.warn("capstone_chat_group attempt {}/{} failed for project {}: {}",
                        attempt, CHAT_GROUP_ATTEMPTS, projectId, e.getMessage());
                try {
                    Thread.sleep(CHAT_GROUP_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Transactional
    public void detachMentor(CapstoneProject project, Person mentor) {
        project.removeMentor(mentor);
        capstoneRepository.save(project);
        linkedGroup(project).ifPresent(group -> revokeGroupMentor(group, mentor, project));
    }

    /** Sets (or clears, with null) the project's student group and moves mentor access with it. */
    @Transactional
    public void linkGroup(CapstoneProject project, Long groupId) {
        Optional<Groups> previous = linkedGroup(project);
        project.setGroupId(groupId);
        capstoneRepository.save(project);

        previous.ifPresent(group -> project.getMentors().forEach(mentor -> revokeGroupMentor(group, mentor, project)));
        linkedGroup(project).ifPresent(group -> {
            project.getMentors().forEach(group::addMentor);
            groupsRepository.save(group);
        });
        logger.info("AUDIT capstone_group_linked capstone={} group={}", project.getSlug(), groupId);
    }

    private Optional<Groups> linkedGroup(CapstoneProject project) {
        return project.getGroupId() == null ? Optional.empty() : groupsRepository.findById(project.getGroupId());
    }

    // Keep the mentor on the group if another project linked to the same group still has them.
    private void revokeGroupMentor(Groups group, Person mentor, CapstoneProject leaving) {
        List<CapstoneProject> sameGroup = capstoneRepository.findByGroupId(group.getId());
        boolean stillMentoredElsewhere = sameGroup.stream()
                .filter(project -> !project.getId().equals(leaving.getId()))
                .anyMatch(project -> project.getMentors().contains(mentor));
        if (!stillMentoredElsewhere) {
            group.removeMentor(mentor);
            groupsRepository.save(group);
        }
    }
}
