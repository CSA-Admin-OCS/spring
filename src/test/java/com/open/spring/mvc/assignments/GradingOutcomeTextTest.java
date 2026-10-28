package com.open.spring.mvc.assignments;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The Spring grading pages (common.js) must have text for every outcome the API can return. */
class GradingOutcomeTextTest {

    @Test
    void theGradingPagesHaveTextForEveryOutcome() throws Exception {
        String commonJs = Files.readString(Path.of("src/main/resources/static/js/grading/common.js"));

        for (GradingOutcome outcome : GradingOutcome.values()) {
            assertTrue(commonJs.contains("    " + outcome.name() + ": "), "common.js has no text for " + outcome);
        }
    }
}
