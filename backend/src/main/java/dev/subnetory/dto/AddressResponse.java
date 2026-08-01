package dev.subnetory.dto;

import java.time.OffsetDateTime;

public record AddressResponse(
        Long id,
        String address,
        String mac,
        String hostname,
        String description,
        Long contextId,
        String contextName,
        Long siteId,
        String siteName,
        Long subnetId,
        String subnetNetwork,
        String modifiedBy,
        boolean temporary,
        OffsetDateTime lastSeenAt,
        String discoverySource,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
