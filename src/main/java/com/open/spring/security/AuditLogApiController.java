package com.open.spring.security;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditLogApiController {

    @Autowired
    private AuditLogJpaRepository auditLogRepository;

    @GetMapping("/logs")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_MENTOR', 'ROLE_TEACHER')")
    public ResponseEntity<List<Map<String, Object>>> getLogs(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String eventType) {

        List<AuditLog> logs = (eventType != null && !eventType.isBlank())
                ? auditLogRepository.findByEventTypeOrderByTimestampDesc(eventType)
                : auditLogRepository.findAllByOrderByTimestampDesc();

        List<Map<String, Object>> result = logs.stream()
                .limit(limit)
                .map(AuditLog::toMap)
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }
}
