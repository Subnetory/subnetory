package dev.subnetory.dto;

/**
 * Resultat de l'application d'un template : la demande de series enchainees calculee
 * ({@code request}, directement reutilisable sur {@code /addresses/series/groups}) et son
 * apercu ({@code preview}, adresses, noms et statuts), sans rien enregistrer.
 */
public record SeriesTemplateExpansionResponse(
        SeriesGroupsRequest request,
        SeriesGroupsResponse preview
) {}
