package com.open.spring.mvc.aiconnections;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroqOutputBudgetTest {
    @Test void normalBudgetGrowsWithRequiredScores() {
        assertEquals(new GroqOutputBudget(384, false), GroqOutputBudget.forHackCount(0));
        assertEquals(new GroqOutputBudget(512, false), GroqOutputBudget.forHackCount(2));
        assertEquals(new GroqOutputBudget(768, false), GroqOutputBudget.forHackCount(6));
    }
    @Test void compressionPreservesRoomForEveryScoreBeforeSkipping() {
        assertEquals(new GroqOutputBudget(480, true), GroqOutputBudget.forHackCount(7));
        assertEquals(new GroqOutputBudget(800, true), GroqOutputBudget.forHackCount(17));
        assertFalse(GroqOutputBudget.forHackCount(18).fits());
        assertFalse(GroqOutputBudget.forHackCount(Integer.MAX_VALUE).fits());
        assertThrows(IllegalArgumentException.class, () -> GroqOutputBudget.forHackCount(-1));
    }
}
