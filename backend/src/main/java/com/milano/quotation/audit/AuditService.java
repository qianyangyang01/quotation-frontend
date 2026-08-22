package com.milano.quotation.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditLogRepository logs; private final ObjectMapper mapper;
    public AuditService(AuditLogRepository logs, ObjectMapper mapper) { this.logs = logs; this.mapper = mapper; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String outcome, Map<String, ?> detail) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var log = new AuditLog(); log.id = UUID.randomUUID(); log.requestId = value(MDC.get("requestId"), "system");
        log.actorAccount = authentication == null ? "anonymous" : value(authentication.getName(), "anonymous");
        log.action = action; log.resourceType = resourceType; log.resourceId = resourceId; log.outcome = outcome;
        log.detail = mapper.valueToTree(detail); log.createdAt = Instant.now(); logs.save(log);
    }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
