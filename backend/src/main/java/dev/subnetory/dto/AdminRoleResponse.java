package dev.subnetory.dto;

public record AdminRoleResponse(
        Long id,
        String name,
        String label,
        String description
) {}
