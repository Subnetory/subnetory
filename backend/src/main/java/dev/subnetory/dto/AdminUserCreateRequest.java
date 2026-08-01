package dev.subnetory.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

public record AdminUserCreateRequest(
        String username,
        String email,
        String temporaryPassword,
        boolean enabled,
        @Schema(description = "IDs des rôles attribuables retournés par GET /api/v1/admin/users/assignable-roles.")
        Set<Long> roleIds,
        Set<Long> contextIds
) {}
