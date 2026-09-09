package dev.subnetory.backup;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Etat "restauration en cours" partage entre {@link BackupExecutionService}
 * et {@code dev.subnetory.security.RestoreMaintenanceFilter} (correctif
 * securite MOYENNE, audit 04/08/2026 ; barriere de drainage ajoutee le
 * 04/08/2026 suite a un troisieme audit externe, constat M-01).
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
 *
 * <p><strong>Barriere de drainage (troisieme audit externe, constat M-01) :</strong>
 * {@link #begin()} seul ne fermait que l'admission de NOUVELLES mutations —
 * une requete deja admise juste avant restait en vol, potentiellement
 * jusqu'apres la fin du {@code pg_restore --single-transaction} qui suit
 * immediatement, ecrivant alors dans la base fraichement restauree plutot
 * que d'etre simplement ralentie derriere ses verrous (l'analyse initiale
 * du 04/08/2026 sous-estimait ce risque). {@link #tryAdmitMutation()} et
 * {@link #begin()} partagent desormais le meme moniteur : une requete est
 * soit admise et comptee AVANT que {@code begin()} ne puisse s'executer,
 * soit rejetee par une bascule deja effective — aucune fenetre ou les deux
 * se chevauchent. {@link #awaitDrain(Duration)} attend ensuite, avec un
 * delai borne, que toutes les mutations deja admises se terminent avant que
 * l'appelant ne lance {@code pg_restore}.</p>
 */
@Component
public class RestoreMaintenanceGate {

    private final Object lock = new Object();
    private boolean restoreInProgress = false;
    private int activeMutations = 0;
    private final ThreadLocal<Integer> mutationsAdmittedOnCurrentThread =
            ThreadLocal.withInitial(() -> 0);

    /**
     * Tente d'admettre une nouvelle mutation. Retourne {@code false} si une
     * restauration est active — dans ce cas la requete n'a JAMAIS ete
     * comptee et doit etre rejetee sans jamais atteindre {@code doFilter}.
     * Si {@code true}, l'appelant DOIT appeler {@link #releaseMutation()}
     * dans un bloc {@code finally} correspondant, que la requete reussisse
     * ou echoue.
     */
    public boolean tryAdmitMutation() {
        synchronized (lock) {
            if (restoreInProgress) {
                return false;
            }
            activeMutations++;
            mutationsAdmittedOnCurrentThread.set(mutationsAdmittedOnCurrentThread.get() + 1);
            return true;
        }
    }

    /** Contrepartie obligatoire d'un {@link #tryAdmitMutation()} ayant retourne {@code true}. */
    public void releaseMutation() {
        synchronized (lock) {
            int ownedByCurrentThread = mutationsAdmittedOnCurrentThread.get();
            if (ownedByCurrentThread <= 0) {
                throw new IllegalStateException("No admitted mutation belongs to the current thread");
            }
            if (ownedByCurrentThread == 1) {
                mutationsAdmittedOnCurrentThread.remove();
            } else {
                mutationsAdmittedOnCurrentThread.set(ownedByCurrentThread - 1);
            }
            activeMutations--;
            if (activeMutations <= 0) {
                lock.notifyAll();
            }
        }
    }

    /**
     * Active le mode maintenance : a partir de cet appel, plus aucun appel a
     * {@link #tryAdmitMutation()} ne peut reussir. Les mutations deja
     * admises avant cet appel restent actives — voir {@link #awaitDrain(Duration)}.
     */
    public void begin() {
        synchronized (lock) {
            restoreInProgress = true;
        }
    }

    public void end() {
        synchronized (lock) {
            restoreInProgress = false;
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return restoreInProgress;
        }
    }

    /**
     * Attend, apres {@link #begin()}, que toutes les mutations deja admises
     * se terminent — borne par {@code timeout} pour ne jamais bloquer
     * indefiniment une restauration a cause d'une requete anormalement
     * lente ou bloquee (ce qui serait sinon un deni de service trivial :
     * il suffirait de maintenir une connexion mutante ouverte). Retourne
     * {@code true} si le drainage est complet avant l'expiration du delai,
     * {@code false} sinon (l'appelant journalise alors le nombre de
     * mutations encore actives et peut choisir de proceder quand meme —
     * ce residu redevient alors le meme risque borne, plutot que
     * systematique, que documentait initialement {@code RestoreMaintenanceFilter}).
     */
    public boolean awaitDrain(Duration timeout) throws InterruptedException {
        synchronized (lock) {
            int ownedByCurrentThread = mutationsAdmittedOnCurrentThread.get();
            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            while (activeMutations > ownedByCurrentThread) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return activeMutations <= ownedByCurrentThread;
                }
                long remainingMillis = Duration.ofNanos(remainingNanos).toMillis();
                lock.wait(Math.max(remainingMillis, 1));
            }
            return true;
        }
    }

    /** Nombre de mutations HTTP actuellement admises et non terminees — usage diagnostic/tests. */
    public int activeMutationCount() {
        synchronized (lock) {
            return activeMutations;
        }
    }

    /** Mutations encore en vol, hors requête de restauration portée par le thread appelant. */
    public int activeMutationCountExcludingCurrentThread() {
        synchronized (lock) {
            return Math.max(0, activeMutations - mutationsAdmittedOnCurrentThread.get());
        }
    }
}
