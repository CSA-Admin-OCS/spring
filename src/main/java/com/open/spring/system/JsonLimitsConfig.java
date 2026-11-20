package com.open.spring.system;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.StreamReadConstraints;

/**
 * Lets one JSON string be as long as a request may be. A file shared in a group chat is uploaded
 * as one base64 string ({filename, base64Data}), and Jackson refuses any string over 20,000,000
 * characters by default, which stopped uploads at about 14 MB. A 35 MB file is about 49 million
 * characters as base64. nginx already caps a request body at 50 MB (client_max_body_size in
 * nginx_spring_8585_8589.conf), so one string may now be as long as that cap allows.
 */
@Configuration
public class JsonLimitsConfig {

    public static final int MAX_STRING_LENGTH = 50_000_000;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longJsonStrings() {
        return builder -> builder.postConfigurer(mapper -> mapper.getFactory().setStreamReadConstraints(
                StreamReadConstraints.builder().maxStringLength(MAX_STRING_LENGTH).build()));
    }
}
