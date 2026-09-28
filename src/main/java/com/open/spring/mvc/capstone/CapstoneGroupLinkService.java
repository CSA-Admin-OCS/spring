package com.open.spring.mvc.capstone;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
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
 */
@Service
@RequiredArgsConstructor
public class CapstoneGroupLinkService {
    private static final Logger logger = LoggerFactory.getLogger(CapstoneGroupLinkService.class);

    private final CapstoneProjectJpaRepository capstoneRepository;
    private final GroupsJpaRepository groupsRepository;

    @Transactional
    public void attachMentor(CapstoneProject project, Person mentor) {
        project.addMentor(mentor);
        capstoneRepository.save(project);
        linkedGroup(project).ifPresent(group -> {
            group.addMentor(mentor);
            groupsRepository.save(group);
        });
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
