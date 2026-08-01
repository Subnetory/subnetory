package dev.subnetory.dto;

import java.time.OffsetDateTime;

/** Réponse REST pour un contexte réseau / VRF. */
public record NetworkContextResponse(
        Long id,
        String name,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
