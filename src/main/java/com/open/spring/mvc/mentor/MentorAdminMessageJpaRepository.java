package com.open.spring.mvc.mentor;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MentorAdminMessageJpaRepository extends JpaRepository<MentorAdminMessage, Long> {
    List<MentorAdminMessage> findByMentorUidOrderByIdAsc(String mentorUid);

    List<MentorAdminMessage> findAllByOrderByIdAsc();
}
