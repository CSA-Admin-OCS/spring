package com.open.spring.mvc.assignments;

import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.assignment;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.student;
import static com.open.spring.mvc.assignments.AssignmentCreatorFixtures.submission;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SubmissionSimilarityTest {

    private static final Assignment PILOT = assignment(10L, "pilot");

    private static AssignmentSubmission work(long id, String uid, Map<String, Object> content) {
        AssignmentSubmission submission = submission(id, PILOT);
        submission.setSubmitter(student(id, uid));
        submission.setContent(content);
        return submission;
    }

    @Test
    void theSameLinkMatchesWhateverItsSchemeCaseOrTrailingSlash() {
        AssignmentSubmission hop = work(1, "hop", Map.of("url", "https://github.com/hop/repo/issues/3"));
        AssignmentSubmission copy = work(2, "lex", Map.of("url", "HTTP://www.GitHub.com/hop/repo/issues/3/?tab=1#top"));
        AssignmentSubmission other = work(3, "alan", Map.of("url", "https://github.com/alan/repo/issues/3"));

        List<SubmissionSimilarity.Match> matches = SubmissionSimilarity.findMatches(hop, List.of(hop, copy, other));

        assertEquals(1, matches.size());
        assertEquals(2L, matches.get(0).submissionId());
        assertEquals("lex", matches.get(0).submitterName());
        assertEquals(Set.of(SubmissionSimilarity.Basis.SAME_LINK), matches.get(0).basis());
    }

    @Test
    void identicalLongAnswersMatchButShortOnesDoNot() {
        String answer = "Inheritance lets a subclass reuse and extend the behavior of its superclass.";
        AssignmentSubmission hop = work(1, "hop", Map.of("text", answer));
        AssignmentSubmission copy = work(2, "lex", Map.of("text", "  " + answer.toUpperCase().replace(" ", "   ") + " "));
        AssignmentSubmission shortA = work(3, "alan", Map.of("text", "done"));
        AssignmentSubmission shortB = work(4, "madam", Map.of("text", "done"));

        assertEquals(Set.of(SubmissionSimilarity.Basis.SAME_TEXT),
                SubmissionSimilarity.findMatches(hop, List.of(copy)).get(0).basis());
        assertTrue(SubmissionSimilarity.findMatches(shortA, List.of(shortB)).isEmpty(), "short answers match by chance");
        assertNull(SubmissionSimilarity.Fingerprint.normalizeText("short"));
    }

    @Test
    void aFileMatchesOnlyOnTheSameNameAndSize() {
        AssignmentSubmission hop = work(1, "hop", Map.of("filename", "Lab.ipynb", "size", 2048));
        AssignmentSubmission copy = work(2, "lex", Map.of("filename", "lab.ipynb", "size", 2048));
        AssignmentSubmission resized = work(3, "alan", Map.of("filename", "Lab.ipynb", "size", 4096));

        List<SubmissionSimilarity.Match> matches = SubmissionSimilarity.findMatches(hop, List.of(copy, resized));

        assertEquals(1, matches.size());
        assertEquals(Set.of(SubmissionSimilarity.Basis.SAME_FILE), matches.get(0).basis());
    }

    @Test
    void emptyContentNeverMatches() {
        AssignmentSubmission empty = work(1, "hop", null);
        AssignmentSubmission blank = work(2, "lex", Map.of("url", " ", "text", ""));

        assertTrue(SubmissionSimilarity.findMatches(empty, List.of(blank)).isEmpty());
    }
}
