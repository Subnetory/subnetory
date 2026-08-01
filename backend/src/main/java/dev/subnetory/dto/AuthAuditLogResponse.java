package dev.subnetory.dto;

import java.time.OffsetDateTime;

public record AuthAuditLogResponse(
        Long id,
        String eventType,
        String username,
        String targetUsername,
        String ipAddress,
        String userAgent,
        boolean success,
        String message,
        OffsetDateTime createdAt
) {}
