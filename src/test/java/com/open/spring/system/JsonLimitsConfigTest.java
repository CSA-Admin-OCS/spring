package com.open.spring.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.groups.GroupChatApiController;

/**
 * A shared file is uploaded as one base64 string in a JSON body. Jackson refuses a string over
 * 20,000,000 characters by default, which stopped uploads at about 14 MB; Messages allows 35 MB.
 */
class JsonLimitsConfigTest {

    /** The ObjectMapper Spring builds for request bodies, with this app's customizer applied. */
    private static ObjectMapper requestMapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JsonLimitsConfig().longJsonStrings().customize(builder);
        return builder.build();
    }

    @Test
    void anUploadPastJacksonsDefaultStringLimitIsRead() throws Exception {
        String base64 = "A".repeat(20_000_004);
        String body = "{\"filename\":\"video.mp4\",\"base64Data\":\"" + base64 + "\"}";

        GroupChatApiController.FileUploadRequest request =
                requestMapper().readValue(body, GroupChatApiController.FileUploadRequest.class);

        assertEquals(base64.length(), request.getBase64Data().length());
    }

    @Test
    void springBootsObjectMapperGetsTheLimit() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JsonLimitsConfig.class)
                .run(context -> assertEquals(JsonLimitsConfig.MAX_STRING_LENGTH,
                        context.getBean(ObjectMapper.class).getFactory().streamReadConstraints().getMaxStringLength()));
    }

    @Test
    void aThirtyFiveMegabyteFileFitsAsBase64() {
        long base64OfThirtyFiveMegabytes = 4 * ((35L * 1024 * 1024 + 2) / 3);
        int limit = requestMapper().getFactory().streamReadConstraints().getMaxStringLength();
        assertTrue(limit >= base64OfThirtyFiveMegabytes, limit + " < " + base64OfThirtyFiveMegabytes);
    }
}
