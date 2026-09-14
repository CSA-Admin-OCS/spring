package com.open.spring.mvc.questboard;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestboardJpaRepository extends JpaRepository<Questboard, Long> {
}
