package dev.subnetory.scan;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Réponse de POST /api/v1/subnets/{id}/scan.
 */
public record ScanResponse(
        Long subnetId,
        String network,
        String source,
        int hostsFound,
        @Schema(description = "Hôtes détectés pendant le scan. La liste contient uniquement les réponses positives.")
        List<ScanHost> hosts,
        int created,
        int updatedLastSeen,
        int overwritten,
        int errors,
        List<String> errorDetails,
        @Schema(description = "Commande générée par Subnetory avec les options autorisées.",
                example = "nmap -sn -R --dns-servers 192.168.1.254 -oX - 192.168.1.0/24")
        String commandPreview,
        int exitCode,
        @Schema(description = "Journal d'exécution synthétique affichable côté client.")
        List<String> logLines,
        OffsetDateTime scannedAt
) {
    public record ScanHost(
            String ip,
            String mac,
            String hostname
    ) {}
}
