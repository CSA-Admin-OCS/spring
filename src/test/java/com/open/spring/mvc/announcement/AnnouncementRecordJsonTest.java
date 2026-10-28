package com.open.spring.mvc.announcement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.announcement.AnnouncementApiRestController.AnnouncementRecord;

/** Keeps the JSON the announcement API returns the same as before it had a DTO. */
class AnnouncementRecordJsonTest {

    @Test
    void recordCopiesTheEntityAndKeepsTheJsonFieldOrder() throws Exception {
        AnnouncementEntity entity = new AnnouncementEntity(7L, "Ada", "Quiz Friday", "Unit 3 quiz",
                "2026-10-27 09:00:00", "csa");

        String json = new ObjectMapper().writeValueAsString(AnnouncementRecord.of(entity));

        assertEquals("{\"id\":7,\"author\":\"Ada\",\"title\":\"Quiz Friday\",\"body\":\"Unit 3 quiz\","
                + "\"timestamp\":\"2026-10-27 09:00:00\",\"tags\":\"csa\"}", json);
    }
}
