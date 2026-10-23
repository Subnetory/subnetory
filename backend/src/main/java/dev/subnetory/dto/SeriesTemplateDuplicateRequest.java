package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Copie d'un template (preset ou personnalise) en template personnalise du contexte
 * {@code contextId}, sous le nom {@code name}.
 */
public record SeriesTemplateDuplicateRequest(
        @NotNull(message = "Context ID is required")
        Long contextId,

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name
) {}
