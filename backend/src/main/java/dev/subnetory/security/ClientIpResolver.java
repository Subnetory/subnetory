package dev.subnetory.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolution de l'adresse IP cliente pour le rate limiting.
 *
 * <h3>Modele de confiance (correctif securite H1)</h3>
 * <p>Les en-tetes {@code X-Forwarded-For} et {@code X-Real-IP} sont forgeables
 * par n'importe quel client. S'y fier sans precaution permet a un attaquant :</p>
 * <ul>
 *   <li>de contourner le rate limiting (IP differente a chaque requete) ;</li>
 *   <li>de verrouiller un tiers en usurpant son IP (DoS cible).</li>
 * </ul>
 *
 * <p>Par defaut, ce resolveur IGNORE les en-tetes et n'utilise que
 * {@link HttpServletRequest#getRemoteAddr()}, qui n'est pas falsifiable par le
 * client. C'est le comportement sur pour un deploiement direct (sans proxy).</p>
 *
 * <p>Si Subnetory est deploye derriere un reverse proxy de confiance (Nginx,
 * Traefik, HAProxy...) qui injecte lui-meme {@code X-Forwarded-For}, activer
 * {@code subnetory.security.trusted-proxy=true}. Les en-tetes ne sont alors lus
 * que lorsque la connexion directe ({@code getRemoteAddr()}) provient d'une
 * plage declaree dans {@code subnetory.security.trusted-proxy-cidrs}.</p>
 */
@Component
public class ClientIpResolver {

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final String UNKNOWN = "unknown";

    /**
     * Active la lecture des en-tetes de transfert. Defaut : false (sur).
     * A activer uniquement derriere un proxy frontal maitrise.
     */
    private final boolean trustedProxyEnabled;

    /**
     * Liste, separee par des virgules, de prefixes CIDR ou d'IP exactes
     * autorisees a presenter X-Forwarded-For. Exemple :
     * {@code 10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}.
     *
     * <p>Obligatoire si trusted-proxy=true (audit 03/08/2026, correctif
     * ELEVEE) : {@link #validateConfiguration()} refuse le demarrage de
     * l'application si cette liste est vide alors que le mode proxy est
     * actif, plutot que d'accepter silencieusement n'importe quelle
     * connexion directe comme source d'en-tete de confiance.</p>
     */
    private final String[] trustedProxyCidrs;

    public ClientIpResolver(
            @Value("${subnetory.security.trusted-proxy:false}") boolean trustedProxyEnabled,
            @Value("${subnetory.security.trusted-proxy-cidrs:}") String trustedProxyCidrs) {
        this.trustedProxyEnabled = trustedProxyEnabled;
        this.trustedProxyCidrs = splitCidrs(trustedProxyCidrs);
    }

    /**
     * Audit 03/08/2026, correctif ELEVEE : jusqu'ici, activer
     * {@code trusted-proxy=true} sans renseigner {@code trusted-proxy-cidrs}
     * etait accepte silencieusement et faisait confiance a
     * X-Forwarded-For/X-Real-IP en provenance de N'IMPORTE QUELLE connexion
     * directe (voir {@link #isFromTrustedProxy} : liste vide => tout accepte).
     * Un operateur qui active le mode proxy sans penser a restreindre la
     * source (ou qui laisse une valeur vide par erreur de configuration)
     * ouvrait alors la porte a une usurpation d'IP par n'importe quel client
     * pouvant atteindre l'application, contournant le rate limiting et
     * faussant le journal d'audit. Refuse desormais de demarrer plutot que
     * de degrader silencieusement la protection.
     */
    @PostConstruct
    void validateConfiguration() {
        if (trustedProxyEnabled && trustedProxyCidrs.length == 0) {
            throw new IllegalStateException(
                    "subnetory.security.trusted-proxy est active mais "
                            + "subnetory.security.trusted-proxy-cidrs est vide : "
                            + "n'importe quelle connexion directe serait alors traitee "
                            + "comme un proxy de confiance. Renseignez au moins une plage "
                            + "CIDR (ex. l'adresse du reseau Docker/Kubernetes du reverse "
                            + "proxy), ou desactivez trusted-proxy si aucun reverse proxy "
                            + "de confiance n'est en place.");
        }
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = trim(request.getRemoteAddr());

        // Mode sur par defaut : seule la source TCP reelle compte.
        if (!trustedProxyEnabled) {
            return hasText(remoteAddr) ? remoteAddr : UNKNOWN;
        }

        // Mode proxy : on ne fait confiance aux en-tetes que si la connexion
        // directe provient d'un proxy declare de confiance.
        if (!isFromTrustedProxy(remoteAddr)) {
            return hasText(remoteAddr) ? remoteAddr : UNKNOWN;
        }

        String forwardedFor = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (hasText(forwardedFor)) {
            return extractFirstForwardedIp(forwardedFor);
        }

        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (hasText(realIp)) {
            return realIp.trim();
        }

        return hasText(remoteAddr) ? remoteAddr : UNKNOWN;
    }

    private String extractFirstForwardedIp(String forwardedFor) {
        String[] parts = forwardedFor.split(",");
        if (parts.length == 0) {
            return UNKNOWN;
        }
        String first = parts[0].trim();
        return hasText(first) ? first : UNKNOWN;
    }

    /**
     * Verifie si l'IP source directe appartient a la liste des proxys de confiance.
     * Supporte les IP exactes et les prefixes CIDR IPv4. En cas de format
     * inattendu, renvoie false (fail-safe : on ne fait pas confiance).
     */
    private boolean isFromTrustedProxy(String remoteAddr) {
        if (!hasText(remoteAddr)) {
            return false;
        }
        // Aucune plage declaree : l'operateur a active le mode proxy sans
        // restreindre la source. On accepte alors tout proxy direct.
        if (trustedProxyCidrs.length == 0) {
            return true;
        }
        for (String entry : trustedProxyCidrs) {
            if (matchesCidrOrExact(remoteAddr, entry)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesCidrOrExact(String ip, String entry) {
        try {
            if (!entry.contains("/")) {
                return entry.equals(ip);
            }
            String[] cidr = entry.split("/", 2);
            String network = cidr[0];
            int prefix = Integer.parseInt(cidr[1]);

            long ipLong = ipv4ToLong(ip);
            long netLong = ipv4ToLong(network);
            if (ipLong < 0 || netLong < 0) {
                return false; // IPv6 ou format invalide : non gere ici, fail-safe
            }
            long mask = prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (ipLong & mask) == (netLong & mask);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Convertit une IPv4 en entier non signe. Renvoie -1 si non IPv4. */
    private long ipv4ToLong(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            return -1;
        }
        long result = 0;
        for (String octet : octets) {
            int value = Integer.parseInt(octet);
            if (value < 0 || value > 255) {
                return -1;
            }
            result = (result << 8) | value;
        }
        return result;
    }

    private static String[] splitCidrs(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        String[] parts = raw.split(",");
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                cleaned.add(trimmed);
            }
        }
        return cleaned.toArray(new String[0]);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
