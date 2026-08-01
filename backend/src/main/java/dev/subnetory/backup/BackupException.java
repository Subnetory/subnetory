package dev.subnetory.backup;

/**
 * Exception levee lors d'une erreur de sauvegarde ou de restauration de la
 * base de donnees. Phase 7 audit, 31/07/2026 — meme pattern que
 * {@code dev.subnetory.scan.ScanException} pour l'execution de processus
 * externes ({@code pg_dump}/{@code pg_restore}).
 */
public class BackupException extends Exception {

    public enum Reason {
        /** pg_dump/pg_restore absent ou inaccessible. */
        TOOL_NOT_AVAILABLE,
        /** L'operation n'a pas termine dans la duree autorisee. */
        TIMEOUT,
        /** Erreur lors de l'execution du processus externe. */
        EXECUTION_FAILED,
        /** Fichier de sauvegarde introuvable ou illisible. */
        FILE_NOT_FOUND,
        /** Texte de confirmation de restauration incorrect. */
        CONFIRMATION_MISMATCH,
        /** Sauvegarde de securite pre-restauration en echec — restauration annulee par prudence. */
        SAFETY_BACKUP_FAILED,
        /**
         * Operation refusee a cause de l'etat courant de la ressource (audit
         * 01/08/2026) : ex. suppression d'une sauvegarde encore referencee par
         * une restauration conservee, ou encore en cours (RUNNING). Distinct de
         * {@link #CONFIRMATION_MISMATCH} (saisie utilisateur incorrecte) —
         * ici l'utilisateur n'a rien saisi de travers, c'est l'etat des
         * donnees qui interdit l'operation. Mappe sur HTTP 409.
         */
        CONFLICT
    }

    private final Reason reason;

    public BackupException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
