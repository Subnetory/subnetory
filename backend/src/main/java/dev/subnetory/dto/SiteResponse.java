package dev.subnetory.dto;

import java.time.OffsetDateTime;

public record SiteResponse(
        Long id,
        String name,
        String code,
        Long contextId,
        String contextName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
