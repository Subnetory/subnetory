package dev.subnetory.dto;

import java.time.OffsetDateTime;

/**
 * Template de reservation en serie.
 *
 * @param key        identifiant stable : {@code builtin:<cle>} (preset livre, lecture seule,
 *                   global) ou {@code custom:<id>} (template d'un contexte)
 * @param builtin    {@code true} pour un preset livre
 * @param id         identifiant du template personnalise ({@code null} pour un preset)
 * @param contextId  contexte du template personnalise ({@code null} pour un preset)
 * @param updatedAt  derniere modification ({@code null} pour un preset)
 */
public record SeriesTemplateResponse(
        String key,
        boolean builtin,
        Long id,
        Long contextId,
        String name,
        String description,
        SeriesTemplateDefinition definition,
        OffsetDateTime updatedAt
) {}
