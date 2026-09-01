package com.open.spring.mvc.capstone;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CapstoneProjectJpaRepository extends JpaRepository<CapstoneProject, Long> {

    Optional<CapstoneProject> findBySlug(String slug);

    List<CapstoneProject> findAllByOrderByTitleAsc();

    // Projects a person mentors. Mirrors GroupsJpaRepository.findGroupsByMentorUid.
    @Query("SELECT c FROM CapstoneProject c JOIN c.mentors m WHERE m.uid = :uid ORDER BY c.title")
    List<CapstoneProject> findByMentorUid(@Param("uid") String uid);

    @Query("SELECT c FROM CapstoneProject c JOIN c.mentors m WHERE m.id = :personId ORDER BY c.title")
    List<CapstoneProject> findByMentorId(@Param("personId") Long personId);

    // Raw read so the chat/admin endpoints can list mentors without a transaction --
    // same reason GroupsJpaRepository has findGroupMembersRaw.
    @Query(value = "SELECT p.id, p.uid, p.name, p.email FROM capstone_mentors cm "
                 + "JOIN person p ON cm.person_id = p.id "
                 + "WHERE cm.capstone_id = :capstoneId ORDER BY p.id", nativeQuery = true)
    List<Object[]> findMentorsRaw(@Param("capstoneId") Long capstoneId);
}
