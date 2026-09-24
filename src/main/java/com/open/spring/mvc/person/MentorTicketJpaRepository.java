package com.open.spring.mvc.person;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MentorTicketJpaRepository extends JpaRepository<MentorTicket, Long> {
    List<MentorTicket> findByResolvedFalseOrderByIdDesc();

    // Most recent ticket for a uid, resolved or not -- lets the signed-in account itself
    // check "am I a pending mentor applicant" without needing admin-only access.
    Optional<MentorTicket> findFirstByUidOrderByIdDesc(String uid);
}
