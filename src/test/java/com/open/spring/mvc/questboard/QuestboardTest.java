package com.open.spring.mvc.questboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class QuestboardTest {

    @Test
    public void testQuestboard() {

        Questboard quest = new Questboard(
                1L,
                "Create a Plan",
                "Write a plan for Spring 1 Final",
                "HARD",
                250,
                "OPEN",
                "2026-09-14"
        );

        // Test getters
        assertEquals(1L, quest.getId());
        assertEquals("Create a Plan", quest.getTitle());
        assertEquals("Write a plan for Spring 1 Final", quest.getDescription());
        assertEquals("HARD", quest.getDifficulty());
        assertEquals(250, quest.getXp());
        assertEquals("OPEN", quest.getStatus());
        assertEquals("2026-09-14", quest.getDeadline());

        // Test setter
        quest.setStatus("DONE");
        assertEquals("DONE", quest.getStatus());
    }
}