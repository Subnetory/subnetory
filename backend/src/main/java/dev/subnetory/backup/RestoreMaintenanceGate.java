package dev.subnetory.backup;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Etat "restauration en cours" partage entre {@link BackupExecutionService}
 * et {@code dev.subnetory.security.RestoreMaintenanceFilter} (correctif
 * securite MOYENNE, audit 04/08/2026).
 *
 * <p>{@code BackupExecutionService#operationInProgress} empechait deja toute
 * AUTRE operation de sauvegarde/restauration concurrente, mais ne bloquait
 * jamais les mutations metier normales (creation d'adresse, deplacement de
 * subnet, gestion des utilisateurs...) pendant qu'une restauration ecrase la
 * base sous leurs pieds — {@code RESTORE_OPERATIONS.md} l'exigeait sans que
 * le logiciel ne l'impose. Ce composant, actif uniquement pendant
 * {@link BackupExecutionService#restore}, est lu par le filtre HTTP pour
 * rejeter (503) les requetes de mutation le temps de l'operation.</p>
 *
 * <p>Volontairement distinct de {@code operationInProgress} : une simple
 * sauvegarde ({@code pg_dump}) ne modifie rien et ne justifie pas de bloquer
 * les mutations metier, seule une restauration ({@code pg_restore --clean})
 * le justifie.</p>
 */
@Component
public class RestoreMaintenanceGate {

    private final AtomicBoolean restoreInProgress = new AtomicBoolean(false);

    public void begin() {
        restoreInProgress.set(true);
    }

    public void end() {
        restoreInProgress.set(false);
    }

    public boolean isActive() {
        return restoreInProgress.get();
    }
}
