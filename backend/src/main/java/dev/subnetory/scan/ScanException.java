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
        TOO_MANY_CONCURRENT_SCANS,
        /**
         * Une restauration de sauvegarde est en cours, soit deja au moment
         * de la demande de scan, soit demarree pendant son execution
         * (correctif securite FAIBLE, second audit externe 04/08/2026) :
         * {@code RestoreMaintenanceFilter} bloque bien les NOUVELLES requetes
         * HTTP de mutation, mais un scan deja accepte avant le debut de la
         * restauration continuait auparavant de s'executer (jusqu'a
         * {@code subnetory.scan.timeout-seconds}) et d'ecrire ses resultats
         * via {@link dev.subnetory.service.AddressService#bulkUpsert} sans
         * verification. {@code ScanService#scan} verifie desormais
         * {@code RestoreMaintenanceGate#isActive()} a deux endroits : au
         * tout debut (rejet immediat, sans meme lancer Nmap) et juste avant
         * l'ecriture (couvre le cas ou la restauration demarre pendant le
         * scan). Dans les deux cas, les hotes deja decouverts sont perdus
         * (jamais ecrits) : relancer le scan une fois la restauration
         * terminee.
         */
        RESTORE_IN_PROGRESS
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
