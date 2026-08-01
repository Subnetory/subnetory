package dev.subnetory.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AdminUserResponse(
        Long id,
        String username,
        String email,
        String authType,
        boolean enabled,
        boolean mustChangePassword,
        boolean mfaEnabled,
        List<String> roles,
        List<ContextRef> contexts,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public record ContextRef(Long id, String name) {}
}
