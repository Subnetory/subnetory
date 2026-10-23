package dev.subnetory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Creation ou modification d'un template personnalise de reservation en serie, rattache a
 * un contexte reseau. Le nom est unique (sans tenir compte de la casse) dans le contexte.
 */
public record SeriesTemplateRequest(
        @NotNull(message = "Context ID is required")
        Long contextId,

        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        @NotNull(message = "Definition is required")
        @Valid
        SeriesTemplateDefinition definition
) {}
