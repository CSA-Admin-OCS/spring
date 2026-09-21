package com.open.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.List;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import com.open.spring.mvc.groups.GroupChatService;
import com.open.spring.mvc.groups.Groups;
import com.open.spring.mvc.groups.GroupsJpaRepository;

import lombok.RequiredArgsConstructor;

@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
@Component
@RequiredArgsConstructor
public class StartupService {

    private final GroupsJpaRepository groupsRepository;
    private final GroupChatService groupChatService;

    @Bean
    public ApplicationRunner initializeGroupStorage() {
        return args -> {
            // Fetch all existing groups from the database
            List<Groups> groups = groupsRepository.findAll();

            // Ensure S3 storage exists for each group
            for (Groups group : groups) {
                groupChatService.ensureGroupStorageExists(group.getName());
            }
        };
    }
}