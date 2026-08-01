package dev.subnetory.dto;

public record PasswordChangeRequest(
        String currentPassword,
        String newPassword,
        String confirmPassword
) {}
