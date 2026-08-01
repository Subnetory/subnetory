package dev.subnetory.dto;

/**
 * DTO pour PATCH /api/v1/addresses/{id}.
 *
 * <p>Tous les champs sont optionnels. La distinction entre "absent" et "null"
 * est gérée au niveau du service via {@code Map<String, Object>} issu du
 * corps JSON brut, pas via ce record.</p>
 *
 * <p>Règle :</p>
 * <ul>
 *   <li>Clé absente du JSON → champ non modifié</li>
 *   <li>Clé présente avec valeur null → champ vidé (si nullable en base)</li>
 *   <li>Clé présente avec valeur → champ mis à jour</li>
 * </ul>
 *
 * <p>Ce record sert uniquement à la documentation de l'API.
 * Le service utilise directement {@code Map<String, Object>}.</p>
 */
public record AddressPatchRequest(
        String mac,
        String hostname,
        String description,
        Boolean temporary
) {}
