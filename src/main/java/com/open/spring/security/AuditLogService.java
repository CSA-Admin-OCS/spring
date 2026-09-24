package com.open.spring.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class AuditLogService {

    @Autowired
    private AuditLogJpaRepository auditLogRepository;

    public void log(String eventType, String uid, HttpServletRequest request, String details) {
        AuditLog entry = new AuditLog();
        entry.setEventType(eventType);
        entry.setUid(uid);
        entry.setDetails(details);
        if (request != null) {
            String forwarded = request.getHeader("X-Forwarded-For");
            String ip = forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
            entry.setIpAddress(ip != null && ip.length() > 45 ? ip.substring(0, 45) : ip);
            String ua = request.getHeader("User-Agent");
            entry.setUserAgent(ua != null && ua.length() > 512 ? ua.substring(0, 512) : ua);
        }
        try {
            auditLogRepository.save(entry);
        } catch (Exception e) {
            // Don't let audit logging failures break the main auth flow
        }
    }
}
