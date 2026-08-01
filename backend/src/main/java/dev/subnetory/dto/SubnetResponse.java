package dev.subnetory.dto;

import java.time.OffsetDateTime;

public record SubnetResponse(
        Long id,
        String network,
        String description,
        String gateway,
        Long contextId,
        String contextName,
        Long siteId,
        String siteName,
        Long vlanId,
        String vlanName,
        Long parentId,
        String parentNetwork,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
