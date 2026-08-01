package dev.subnetory.dto;

public record LdapDiagnosticResponse(
        String level,
        String title,
        String message
) {}
