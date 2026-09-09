package com.open.spring.mvc.mentor;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MentorInterestJpaRepository extends JpaRepository<MentorInterest, Long> {
    List<MentorInterest> findByPerson_IdOrderByCreatedAtAsc(Long personId);

    Optional<MentorInterest> findByPerson_IdAndProjectUrl(Long personId, String projectUrl);
}
