package dev.subnetory.scan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * Body optionnel pour POST /api/v1/subnets/{id}/scan.
 *
 * <p>Tous les champs ont des valeurs par défaut — le body peut être omis.</p>
 */
public record ScanRequest(
        /**
         * Méthode de scan. Seul "nmap" est supporté.
         * Valeur par défaut : "nmap".
         */
        @Schema(description = "Moteur de scan. Valeur supportée : nmap.",
                allowableValues = "nmap",
                example = "nmap")
        @Pattern(regexp = "^nmap$", message = "Only 'nmap' is supported in this version")
        String method,

        /**
         * Si true, les champs des entrées existantes sont écrasés avec les données du scan.
         * Si false (défaut), seul last_seen_at est mis à jour pour les IPs existantes.
         */
        @Schema(description = "Écrase les champs existants avec les données détectées. Si false, seules les dates de dernière détection sont mises à jour.",
                example = "false")
        boolean override,

        /**
         * Si true, demande la résolution DNS inverse.
         */
        @Schema(description = "Active la résolution DNS inverse.",
                example = "true")
        Boolean resolveDns,

        /**
         * Si true, conserve le ping ARP de Nmap sur le segment local.
         */
        @Schema(description = "Conserve la détection ARP Nmap sur le segment local.",
                example = "true")
        Boolean arpPing,

        /**
         * Profil de rythme applicatif, converti en option Nmap contrôlée.
         */
        @Schema(description = "Profil de rythme du scan.",
                allowableValues = {"normal", "fast", "gentle"},
                example = "normal")
        @Pattern(regexp = "^(normal|fast|gentle)$", message = "timing must be one of: normal, fast, gentle")
        String timing,

        /**
         * Serveurs DNS utilisés pour affiner la résolution inverse.
         * Plusieurs IPv4 peuvent être séparées par virgule, espace ou retour ligne.
         */
        @Schema(description = "Serveurs DNS utilisés pour la résolution inverse. Séparateurs acceptés : virgule, espace ou retour ligne.",
                example = "192.168.1.254, 1.1.1.1")
        String dnsServers
) {
    /** Constructeur avec valeurs par défaut. */
    public ScanRequest {
        if (method == null || method.isBlank()) method = "nmap";
        if (resolveDns == null) resolveDns = Boolean.TRUE;
        if (arpPing == null) arpPing = Boolean.TRUE;
        if (timing == null || timing.isBlank()) timing = "normal";
        if (dnsServers != null && dnsServers.isBlank()) dnsServers = null;
    }

    public ScanRequest(String method, boolean override) {
        this(method, override, true, true, "normal", null);
    }

    public ScanRequest(String method, boolean override, Boolean resolveDns, Boolean arpPing, String timing) {
        this(method, override, resolveDns, arpPing, timing, null);
    }
}
