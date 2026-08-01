package dev.subnetory.dto;

import java.time.OffsetDateTime;

public record VlanResponse(
        Long id,
        String name,
        Integer vid,
        Long siteId,
        String siteName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
