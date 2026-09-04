package com.open.spring.mvc.person;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MentorTicketJpaRepository extends JpaRepository<MentorTicket, Long> {
    List<MentorTicket> findByResolvedFalseOrderByIdDesc();
}
