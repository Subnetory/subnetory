package dev.subnetory.scan;

/**
 * Exception levée lors d'une erreur de scan réseau.
 * Portée par {@link ScanController} pour produire une réponse HTTP appropriée.
 */
public class ScanException extends Exception {

    public enum Reason {
        /** Subnet trop grand pour le scan synchrone (> /24). */
        SUBNET_TOO_LARGE,
        /** Outil de scan (nmap) absent ou inaccessible. */
        TOOL_NOT_AVAILABLE,
        /** Le scan n'a pas terminé dans la durée autorisée. */
        TIMEOUT,
        /** Option de scan non valide. */
        INVALID_OPTIONS,
        /** Erreur lors de l'exécution du process. */
        EXECUTION_FAILED,
        /** Erreur lors du parsing de la sortie XML. */
        PARSE_ERROR,
        /**
         * Trop de scans Nmap deja en cours, globalement ou pour cet
         * utilisateur (correctif securite FAIBLE/MOYEN, audit 04/08/2026) —
         * voir {@code ScanService#globalScanSemaphore}/{@code activeScansByUser}.
         */
        TOO_MANY_CONCURRENT_SCANS
    }

    private final Reason reason;

    public ScanException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
