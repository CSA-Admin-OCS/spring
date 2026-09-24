package com.open.spring.mvc.capstone;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CapstoneApplicationJpaRepository extends JpaRepository<CapstoneApplication, Long> {
    List<CapstoneApplication> findByResolvedFalseOrderByIdDesc();

    List<CapstoneApplication> findByPersonUidOrderByIdDesc(String uid);

    Optional<CapstoneApplication> findByCapstoneProject_IdAndPersonUidAndResolvedFalse(Long capstoneId, String uid);
}
