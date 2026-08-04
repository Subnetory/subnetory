package dev.subnetory.backup;

import dev.subnetory.domain.BackupRestore;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.repository.BackupRestoreRepository;
import dev.subnetory.repository.BackupRunRepository;
import dev.subnetory.service.BackupConfigurationService;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Moteur de sauvegarde/restauration de la base (Phase 7 audit, 31/07/2026).
 *
 * <p>Execute {@code pg_dump}/{@code pg_restore} comme outils externes via
 * {@link ProcessBuilder}, exactement dans l'application (que celle-ci
 * tourne en Docker Compose ou en Kubernetes) — meme mecanisme partout,
 * a la demande de l'utilisateur : "toutes les versions doivent beneficier
 * des memes fonctionnalites".</p>
 *
 * <p>Convention calquee sur {@code dev.subnetory.scan.ScanService} :
 * arguments en liste (jamais de shell, jamais d'injection), lecture
 * stdout/stderr en parallele, timeout strict avec {@code destroyForcibly()},
 * exception dediee a raison typee.</p>
 *
 * <h3>Securite</h3>
 * <ul>
 *   <li>Mot de passe transmis via la variable d'environnement {@code PGPASSWORD}
 *       du processus, jamais en argument de ligne de commande (invisible dans
 *       {@code ps}/l'historique shell).</li>
 *   <li>Une seule operation de sauvegarde/restauration a la fois
 *       ({@link #operationInProgress}) — evite toute execution concurrente
 *       de deux {@code pg_dump}/{@code pg_restore} sur la meme base.</li>
 *   <li>Restauration : jamais un simple clic (cf. {@code RESTORE_OPERATIONS.md}) —
 *       {@link #restore} exige un texte de confirmation egal au nom exact du
 *       fichier, verifie l'empreinte SHA-256 du fichier avant de l'utiliser,
 *       et prend automatiquement une sauvegarde de securite avant toute
 *       modification (annulee si cette sauvegarde de securite echoue).</li>
 *   <li>Mode maintenance pendant la restauration (correctif securite MOYENNE,
 *       audit 04/08/2026) : {@link RestoreMaintenanceGate} rejette (503) les
 *       mutations metier via {@code dev.subnetory.security.RestoreMaintenanceFilter}
 *       le temps du {@code pg_restore} — jusque-la impose seulement par la
 *       documentation, jamais par le logiciel. Apres un succes, tous les
 *       jetons JWT sont invalides et toutes les sessions Web sont drainees
 *       ({@link #invalidateSessionsAndTokensAfterRestore}), pour ne pas
 *       laisser un etat restaure plus ancien de {@code user_token_invalidations}
 *       ou des sessions en memoire reautoriser des identifiants deja
 *       revoques depuis.</li>
 * </ul>
 *
 * <h3>Retention</h3>
 * <p>{@link #pruneOldBackups} ne supprime jamais les lignes {@link BackupRun}
 * (historique conserve indefiniment pour l'audit), seulement le fichier
 * {@code .dump} sur disque au-dela du nombre de sauvegardes a conserver.</p>
 */
@Service
public class BackupExecutionService {

    private static final Logger log = LoggerFactory.getLogger(BackupExecutionService.class);
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Value("${subnetory.backup.pg-dump-path:pg_dump}")
    private String pgDumpPath;

    @Value("${subnetory.backup.pg-restore-path:pg_restore}")
    private String pgRestorePath;

    @Value("${subnetory.backup.timeout-seconds:1800}")
    private int timeoutSeconds;

    @Value("${subnetory.backup.storage-path:/var/subnetory/backups}")
    private String storagePathStr;

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    /** Taille maximale acceptee pour un fichier de sauvegarde importe (audit 01/08/2026). */
    @Value("${subnetory.backup.import-max-size-bytes:209715200}")
    private long importMaxSizeBytes;

    /**
     * Clé de chiffrement des sauvegardes (audit 01/08/2026, backlog #13).
     * Volontairement jamais stockée en base ni modifiable depuis l'IHM —
     * même conclusion que {@code DB_PASSWORD_ROTATION_FEASIBILITY.md} :
     * lue depuis une variable d'environnement (Docker secret / Kubernetes
     * Secret monté en lecture seule), cohérent avec les autres secrets de
     * l'application ({@code SUBNETORY_ADMIN_DEFAULT_PASSWORD}...).
     * Le chiffrement est automatiquement actif dès que cette valeur est
     * non vide ({@link #isEncryptionEnabled()}) — pas d'interrupteur
     * séparé, pour éviter tout état incohérent "activé sans clé" ou
     * "clé fournie mais oubliée d'activer".
     */
    @Value("${subnetory.backup.encryption.key:}")
    private String encryptionKey;

    private final BackupRunRepository backupRunRepository;
    private final BackupRestoreRepository backupRestoreRepository;
    private final BackupConfigurationService configurationService;
    private final dev.subnetory.service.AuthAuditService authAuditService;
    private final RestoreMaintenanceGate restoreMaintenanceGate;
    private final dev.subnetory.service.UserTokenInvalidationService userTokenInvalidationService;
    private final dev.subnetory.service.SessionInvalidationService sessionInvalidationService;
    private final AtomicBoolean operationInProgress = new AtomicBoolean(false);

    public BackupExecutionService(BackupRunRepository backupRunRepository,
                                  BackupRestoreRepository backupRestoreRepository,
                                  BackupConfigurationService configurationService,
                                  dev.subnetory.service.AuthAuditService authAuditService,
                                  RestoreMaintenanceGate restoreMaintenanceGate,
                                  dev.subnetory.service.UserTokenInvalidationService userTokenInvalidationService,
                                  dev.subnetory.service.SessionInvalidationService sessionInvalidationService) {
        this.backupRunRepository = backupRunRepository;
        this.backupRestoreRepository = backupRestoreRepository;
        this.configurationService = configurationService;
        this.authAuditService = authAuditService;
        this.restoreMaintenanceGate = restoreMaintenanceGate;
        this.userTokenInvalidationService = userTokenInvalidationService;
        this.sessionInvalidationService = sessionInvalidationService;
    }

    /**
     * Utilisateur courant via le contexte de securite Spring (audit
     * 01/08/2026, backlog #27) — utilise uniquement la ou aucun parametre
     * "performedBy"/"username" explicite n'existe deja sur la methode
     * (suppression, purge...), pour ne pas changer la signature de methodes
     * publiques deja largement appelees/testees. Meme pattern que
     * {@code ContextAccessService#currentAuthentication}.
     */
    private String currentUsername() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return authentication.getName();
    }

    public boolean isOperationInProgress() {
        return operationInProgress.get();
    }

    public String storagePath() {
        return storagePathStr;
    }

    // -------------------------------------------------------
    // Réconciliation au démarrage (audit 01/08/2026)
    // -------------------------------------------------------

    /**
     * Marque FAILED toute ligne {@link BackupRun}/{@link BackupRestore} encore
     * au statut RUNNING au demarrage de l'application.
     *
     * <p>{@link #doBackup} et {@link #restore} sont synchrones : la seule
     * facon pour une ligne de rester RUNNING est que la JVM ait ete tuee
     * (rebuild, crash, redemarrage) pendant l'operation — aucun processus ne
     * peut alors jamais venir la conclure. Observe concretement le
     * 31/07/2026 : un {@code docker compose up -d --build} pendant une
     * sauvegarde manuelle a laisse la ligne bloquee "En cours" (taille
     * inconnue) indefiniment, sans aucun moyen de le corriger autrement
     * qu'en base directement. Cette reconciliation tourne une fois, apres le
     * demarrage complet de l'application ({@link ApplicationReadyEvent}),
     * avant qu'aucune nouvelle operation ne puisse etre declenchee.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOrphanedOperations() {
        for (BackupRun run : backupRunRepository.findByStatus(BackupRun.STATUS_RUNNING)) {
            run.setStatus(BackupRun.STATUS_FAILED);
            run.setFinishedAt(OffsetDateTime.now());
            run.setErrorMessage(ORPHANED_OPERATION_MESSAGE);
            backupRunRepository.save(run);
            log.warn("Backup run #{} marque FAILED au demarrage (etait RUNNING, JVM probablement "
                    + "interrompue avant la fin de l'operation).", run.getId());
        }
        for (BackupRestore restore : backupRestoreRepository.findByStatus(BackupRestore.STATUS_RUNNING)) {
            restore.setStatus(BackupRestore.STATUS_FAILED);
            restore.setFinishedAt(OffsetDateTime.now());
            restore.setErrorMessage(ORPHANED_OPERATION_MESSAGE);
            backupRestoreRepository.save(restore);
            log.warn("Backup restore #{} marque FAILED au demarrage (etait RUNNING, JVM probablement "
                    + "interrompue avant la fin de l'operation).", restore.getId());
        }
    }

    private static final String ORPHANED_OPERATION_MESSAGE =
            "Interrompue par un redémarrage ou un arrêt de l'application avant la fin de l'opération.";

    // -------------------------------------------------------
    // Sauvegarde
    // -------------------------------------------------------

    public BackupRun triggerManualBackup(String username) throws BackupException {
        return triggerManualBackup(username, null);
    }

    /** Variante avec titre/commentaire optionnel (audit 01/08/2026, cf. UI "Sauvegarder maintenant"). */
    public BackupRun triggerManualBackup(String username, String label) throws BackupException {
        acquireOrThrow();
        try {
            BackupRun run = doBackup(BackupRun.TRIGGER_MANUAL, username, label);
            authAuditService.recordBackupTriggered(username, BackupRun.TRIGGER_MANUAL, true, run.getFileName());
            return run;
        } catch (BackupException e) {
            authAuditService.recordBackupTriggered(username, BackupRun.TRIGGER_MANUAL, false, e.getMessage());
            throw e;
        } finally {
            operationInProgress.set(false);
        }
    }

    /** Appele par le planificateur (Phase 7) — n'expose pas de checked exception. */
    public void triggerScheduledBackup() {
        if (!operationInProgress.compareAndSet(false, true)) {
            log.info("Sauvegarde planifiee ignoree : une operation est deja en cours.");
            return;
        }
        try {
            BackupRun run = doBackup(BackupRun.TRIGGER_SCHEDULED, null, null);
            authAuditService.recordBackupTriggered(null, BackupRun.TRIGGER_SCHEDULED, true, run.getFileName());
        } catch (BackupException e) {
            log.error("Echec de la sauvegarde planifiee : {}", e.getMessage());
            authAuditService.recordBackupTriggered(null, BackupRun.TRIGGER_SCHEDULED, false, e.getMessage());
        } finally {
            operationInProgress.set(false);
        }
    }

    private BackupRun doBackup(String triggerSource, String triggeredBy) throws BackupException {
        return doBackup(triggerSource, triggeredBy, null);
    }

    private BackupRun doBackup(String triggerSource, String triggeredBy, String label) throws BackupException {
        BackupRun run = new BackupRun();
        run.setTriggerSource(triggerSource);
        run.setStatus(BackupRun.STATUS_RUNNING);
        run.setStartedAt(OffsetDateTime.now());
        run.setTriggeredBy(triggeredBy);
        run.setLabel(normalizeLabel(label));
        run = backupRunRepository.saveAndFlush(run);

        try {
            Path storageDir = ensureStorageDir();
            String fileName = "subnetory-" + FILE_TIMESTAMP.format(OffsetDateTime.now()) + ".dump";
            Path target = storageDir.resolve(fileName);
            DbConnectionInfo conn = parseJdbcUrl(jdbcUrl);

            log.info("Backup started: trigger={} file={}", triggerSource, fileName);
            runPgDump(conn, target);

            boolean encrypted = isEncryptionEnabled();
            Path finalFile = target;
            if (encrypted) {
                Path encryptedTarget = storageDir.resolve(fileName + ENCRYPTED_FILE_SUFFIX);
                try {
                    encryptFile(target, encryptedTarget);
                    Files.delete(target);
                } catch (BackupException | IOException e) {
                    // Ne jamais laisser le dump en clair sur disque si le chiffrement
                    // echoue en cours de route (mieux vaut une sauvegarde en echec
                    // franche qu'un fichier non chiffre silencieux alors qu'une cle
                    // de chiffrement est configuree).
                    Files.deleteIfExists(target);
                    Files.deleteIfExists(encryptedTarget);
                    if (e instanceof BackupException be) throw be;
                    throw new BackupException(
                            "Nettoyage après échec du chiffrement impossible : " + e.getMessage(),
                            BackupException.Reason.EXECUTION_FAILED);
                }
                finalFile = encryptedTarget;
                fileName = fileName + ENCRYPTED_FILE_SUFFIX;
            }

            long size = Files.size(finalFile);
            String checksum = sha256(finalFile);

            run.setStatus(BackupRun.STATUS_SUCCESS);
            run.setFinishedAt(OffsetDateTime.now());
            run.setFileName(fileName);
            run.setFileSizeBytes(size);
            run.setChecksumSha256(checksum);
            run.setEncrypted(encrypted);
            backupRunRepository.save(run);

            log.info("Backup completed: file={} sizeBytes={} encrypted={}", fileName, size, encrypted);
            pruneOldBackups(storageDir);
            return run;
        } catch (BackupException e) {
            run.setStatus(BackupRun.STATUS_FAILED);
            run.setFinishedAt(OffsetDateTime.now());
            run.setErrorMessage(abbreviate(e.getMessage(), 1000));
            backupRunRepository.save(run);
            log.error("Backup failed: trigger={} reason={} message={}", triggerSource, e.getReason(), e.getMessage());
            throw e;
        } catch (IOException e) {
            // Files.size(target) : pg_dump a reussi mais le fichier produit est
            // devenu illisible avant qu'on ait pu en lire la taille (tres rare —
            // suppression concurrente, probleme disque). Meme traitement qu'un
            // echec de sauvegarde classique.
            String message = "Lecture du fichier de sauvegarde impossible après pg_dump : " + e.getMessage();
            run.setStatus(BackupRun.STATUS_FAILED);
            run.setFinishedAt(OffsetDateTime.now());
            run.setErrorMessage(abbreviate(message, 1000));
            backupRunRepository.save(run);
            log.error("Backup failed: trigger={} message={}", triggerSource, message);
            throw new BackupException(message, BackupException.Reason.EXECUTION_FAILED);
        }
    }

    // -------------------------------------------------------
    // Restauration — jamais un simple clic (RESTORE_OPERATIONS.md)
    // -------------------------------------------------------

    public BackupRestore restore(Long backupRunId, String confirmationText, String performedBy)
            throws BackupException {

        BackupRun sourceRun = backupRunRepository.findById(backupRunId)
                .orElseThrow(() -> new BackupException(
                        "Sauvegarde introuvable.", BackupException.Reason.FILE_NOT_FOUND));

        if (!sourceRun.isSuccess() || sourceRun.getFileName() == null) {
            throw new BackupException(
                    "Cette sauvegarde n'est pas exploitable (en echec ou toujours en cours).",
                    BackupException.Reason.FILE_NOT_FOUND);
        }
        if (confirmationText == null || !confirmationText.trim().equals(sourceRun.getFileName())) {
            throw new BackupException(
                    "Le texte de confirmation ne correspond pas exactement au nom du fichier de sauvegarde.",
                    BackupException.Reason.CONFIRMATION_MISMATCH);
        }

        Path storageDir = ensureStorageDir();
        Path sourceFile = storageDir.resolve(sourceRun.getFileName());
        if (!Files.exists(sourceFile)) {
            throw new BackupException(
                    "Fichier de sauvegarde introuvable sur le disque (purge par la retention ?) : "
                            + sourceRun.getFileName(),
                    BackupException.Reason.FILE_NOT_FOUND);
        }
        String currentChecksum = sha256(sourceFile);
        if (sourceRun.getChecksumSha256() != null && !sourceRun.getChecksumSha256().equals(currentChecksum)) {
            throw new BackupException(
                    "L'empreinte SHA-256 du fichier ne correspond plus a celle enregistree lors de la "
                            + "sauvegarde (fichier modifie ou corrompu). Restauration refusee par prudence.",
                    BackupException.Reason.FILE_NOT_FOUND);
        }

        acquireOrThrow();
        try {
            BackupRestore restoreLog = new BackupRestore();
            restoreLog.setBackupRunId(backupRunId);
            restoreLog.setStatus(BackupRestore.STATUS_RUNNING);
            restoreLog.setStartedAt(OffsetDateTime.now());
            restoreLog.setPerformedBy(performedBy);
            restoreLog = backupRestoreRepository.saveAndFlush(restoreLog);

            BackupRun safetyRun;
            try {
                log.info("Pre-restore safety backup started (restore of {} requested by {})",
                        sourceRun.getFileName(), performedBy);
                safetyRun = doBackup(BackupRun.TRIGGER_PRE_RESTORE_SAFETY, performedBy);
                restoreLog.setSafetyBackupRunId(safetyRun.getId());
                backupRestoreRepository.save(restoreLog);
            } catch (BackupException e) {
                restoreLog.setStatus(BackupRestore.STATUS_FAILED);
                restoreLog.setFinishedAt(OffsetDateTime.now());
                restoreLog.setErrorMessage(abbreviate(
                        "Sauvegarde de securite prealable en echec, restauration annulee : " + e.getMessage(),
                        1000));
                backupRestoreRepository.save(restoreLog);
                authAuditService.recordBackupRestored(performedBy, backupRunId, false,
                        "Sauvegarde de securite prealable en echec : " + e.getMessage());
                throw new BackupException(
                        "Restauration annulee : la sauvegarde de securite prealable a echoue. "
                                + "Aucune modification n'a ete apportee a la base.",
                        BackupException.Reason.SAFETY_BACKUP_FAILED);
            }

            try {
                DbConnectionInfo conn = parseJdbcUrl(jdbcUrl);
                log.warn("Restore started: file={} performedBy={} — service interruption expected",
                        sourceRun.getFileName(), performedBy);

                // Mode maintenance applicatif (correctif securite MOYENNE, audit
                // 04/08/2026, cf. RestoreMaintenanceGate) : active seulement a
                // partir d'ici, immediatement avant le pg_restore --clean qui
                // ecrase reellement les tables metier — la sauvegarde de securite
                // prealable ci-dessus est une simple lecture (pg_dump) et ne
                // justifie pas de rejeter les mutations en cours.
                restoreMaintenanceGate.begin();

                Path fileToRestore = sourceFile;
                Path tempPlain = null;
                try {
                    if (sourceRun.isEncrypted()) {
                        tempPlain = Files.createTempFile(storageDir, "restore-decrypted-", ".dump");
                        decryptFile(sourceFile, tempPlain);
                        fileToRestore = tempPlain;
                    }
                    runPgRestore(conn, fileToRestore);
                } catch (IOException e) {
                    throw new BackupException(
                            "Préparation du fichier déchiffré pour la restauration impossible : " + e.getMessage(),
                            BackupException.Reason.EXECUTION_FAILED);
                } finally {
                    // Ne jamais laisser un dump en clair issu du dechiffrement trainer
                    // sur disque, meme si pg_restore a echoue.
                    if (tempPlain != null) {
                        try {
                            Files.deleteIfExists(tempPlain);
                        } catch (IOException e) {
                            log.warn("Suppression du fichier temporaire déchiffré {} impossible : {}",
                                    tempPlain, e.getMessage());
                        }
                    }
                }

                restoreLog.setStatus(BackupRestore.STATUS_SUCCESS);
                restoreLog.setFinishedAt(OffsetDateTime.now());
                backupRestoreRepository.save(restoreLog);
                log.warn("Restore completed: file={} performedBy={}", sourceRun.getFileName(), performedBy);
                authAuditService.recordBackupRestored(performedBy, backupRunId, true, sourceRun.getFileName());

                // Invalidation post-restauration (correctif securite MOYENNE,
                // audit 04/08/2026) : user_token_invalidations et les sessions
                // Web en memoire ne sont pas figees dans le temps de la
                // restauration — un jeton JWT revoque apres la sauvegarde
                // restauree pourrait redevenir valide, et une session Web deja
                // ouverte garderait les autorites chargees avant la
                // restauration. Effectue seulement apres un SUCCESS confirme,
                // jamais sur un chemin d'echec (rien n'a alors ete modifie en
                // base, cf. --single-transaction).
                invalidateSessionsAndTokensAfterRestore(performedBy);

                return restoreLog;
            } catch (BackupException e) {
                restoreLog.setStatus(BackupRestore.STATUS_FAILED);
                restoreLog.setFinishedAt(OffsetDateTime.now());
                restoreLog.setErrorMessage(abbreviate(e.getMessage(), 1000));
                backupRestoreRepository.save(restoreLog);
                log.error("Restore failed: file={} reason={} message={}",
                        sourceRun.getFileName(), e.getReason(), e.getMessage());
                authAuditService.recordBackupRestored(performedBy, backupRunId, false, e.getMessage());
                throw e;
            }
        } finally {
            restoreMaintenanceGate.end();
            operationInProgress.set(false);
        }
    }

    /**
     * Invalide tous les jetons JWT et drainne toutes les sessions Web apres
     * une restauration reussie (correctif securite MOYENNE, audit
     * 04/08/2026). Volontairement resiliente : un echec de cette etape ne
     * doit jamais faire passer une restauration reussie pour un echec (les
     * donnees metier sont deja restaurees a ce stade) — journalise et avale
     * l'erreur plutot que de la propager.
     */
    private void invalidateSessionsAndTokensAfterRestore(String performedBy) {
        try {
            userTokenInvalidationService.invalidateAllTokens(
                    performedBy, dev.subnetory.service.UserTokenInvalidationService.REASON_POST_RESTORE);
            int expiredSessions = sessionInvalidationService.expireAllSessions();
            log.warn("Post-restore invalidation: all JWTs invalidated, {} web session(s) expired.",
                    expiredSessions);
        } catch (RuntimeException e) {
            log.error("Post-restore invalidation (JWT/sessions) failed — restore itself succeeded, "
                    + "but stale credentials issued before the restore may remain valid until they "
                    + "expire naturally: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------
    // Consultation / téléchargement
    // -------------------------------------------------------

    /** {@code true} si le fichier de cette sauvegarde est toujours present sur disque (pas purge). */
    public boolean isFileAvailable(BackupRun run) {
        if (run == null || run.getFileName() == null) return false;
        return Files.exists(Path.of(storagePathStr).resolve(run.getFileName()));
    }

    public Path resolveDownloadableFile(Long backupRunId) throws BackupException {
        BackupRun run = backupRunRepository.findById(backupRunId)
                .orElseThrow(() -> new BackupException(
                        "Sauvegarde introuvable.", BackupException.Reason.FILE_NOT_FOUND));
        if (run.getFileName() == null) {
            throw new BackupException(
                    "Cette sauvegarde n'a pas de fichier associe.", BackupException.Reason.FILE_NOT_FOUND);
        }
        Path file = Path.of(storagePathStr).resolve(run.getFileName());
        if (!Files.exists(file)) {
            throw new BackupException(
                    "Le fichier de cette sauvegarde a ete purge par la retention ou est introuvable.",
                    BackupException.Reason.FILE_NOT_FOUND);
        }
        return file;
    }

    /** Somme des tailles des fichiers de sauvegarde encore presents sur disque. */
    public long totalStorageBytes() {
        long total = 0;
        for (BackupRun run : backupRunRepository.findByStatusOrderByStartedAtDesc(BackupRun.STATUS_SUCCESS)) {
            if (run.getFileSizeBytes() != null && isFileAvailable(run)) {
                total += run.getFileSizeBytes();
            }
        }
        return total;
    }

    // -------------------------------------------------------
    // Import — reutilise integralement le circuit de restauration existant
    // (audit 01/08/2026)
    // -------------------------------------------------------

    /**
     * Importe un fichier {@code .dump} (par exemple telecharge via
     * {@link #resolveDownloadableFile}, puis remis en place apres migration
     * de serveur) comme une nouvelle ligne d'historique exploitable.
     *
     * <p>Volontairement minimal : l'import ne fait qu'ajouter le fichier au
     * stockage et une ligne {@link BackupRun} (trigger
     * {@link BackupRun#TRIGGER_IMPORTED}, statut SUCCESS) — la restauration
     * elle-meme passe ensuite par {@link #restore}, sans code duplique, et
     * beneficie donc automatiquement de toutes ses protections (texte de
     * confirmation, verification SHA-256, sauvegarde de securite
     * prealable).</p>
     *
     * <p>Validation du format avant acceptation : {@code pg_restore --list}
     * liste le contenu de l'archive sans rien modifier en base — un fichier
     * qui n'est pas un dump {@code --format=custom} valide (ou corrompu)
     * est rejete immediatement, plutot que de decouvrir le probleme au
     * moment d'une restauration reelle plus tard.</p>
     */
    public BackupRun importBackup(MultipartFile file, String username) throws BackupException {
        if (file == null || file.isEmpty()) {
            throw new BackupException("Aucun fichier fourni.", BackupException.Reason.EXECUTION_FAILED);
        }
        if (file.getSize() > importMaxSizeBytes) {
            throw new BackupException(
                    "Fichier trop volumineux (" + file.getSize() + " octets, limite "
                            + importMaxSizeBytes + " octets).",
                    BackupException.Reason.EXECUTION_FAILED);
        }

        acquireOrThrow();
        try {
            Path storageDir = ensureStorageDir();
            String baseFileName = "subnetory-import-" + FILE_TIMESTAMP.format(OffsetDateTime.now()) + ".dump";
            Path uploaded = storageDir.resolve(baseFileName);

            try (InputStream in = file.getInputStream()) {
                Files.copy(in, uploaded, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new BackupException(
                        "Impossible d'enregistrer le fichier importe : " + e.getMessage(),
                        BackupException.Reason.EXECUTION_FAILED);
            }

            String fileName;
            boolean encrypted;
            long size;
            String checksum;
            Path finalFile = null;
            Path tempPlainForValidation = null;

            try {
                boolean sourceIsEncrypted = isEncryptedFile(uploaded);

                if (sourceIsEncrypted) {
                    // Fichier deja chiffre (ex. importe depuis une autre instance
                    // Subnetory utilisant la meme cle) : sans la cle configuree ici,
                    // impossible de le valider ou de le restaurer un jour, autant le
                    // refuser tout de suite plutot que de le stocker inutilement.
                    if (!isEncryptionEnabled()) {
                        throw new BackupException(
                                "Ce fichier importé est chiffré mais aucune clé de chiffrement des "
                                        + "sauvegardes n'est configurée sur cette instance : impossible de le "
                                        + "valider ou de l'utiliser pour une restauration.",
                                BackupException.Reason.EXECUTION_FAILED);
                    }
                    tempPlainForValidation = Files.createTempFile(storageDir, "import-verify-", ".dump");
                    decryptFile(uploaded, tempPlainForValidation);
                    validateDumpFormat(tempPlainForValidation);

                    fileName = baseFileName + ENCRYPTED_FILE_SUFFIX;
                    finalFile = storageDir.resolve(fileName);
                    Files.move(uploaded, finalFile, StandardCopyOption.REPLACE_EXISTING);
                    encrypted = true;
                } else {
                    validateDumpFormat(uploaded);

                    if (isEncryptionEnabled()) {
                        fileName = baseFileName + ENCRYPTED_FILE_SUFFIX;
                        finalFile = storageDir.resolve(fileName);
                        encryptFile(uploaded, finalFile);
                        Files.delete(uploaded);
                        encrypted = true;
                    } else {
                        fileName = baseFileName;
                        finalFile = uploaded;
                        encrypted = false;
                    }
                }

                size = Files.size(finalFile);
                checksum = sha256(finalFile);
            } catch (BackupException | IOException e) {
                Files.deleteIfExists(uploaded);
                if (finalFile != null) {
                    Files.deleteIfExists(finalFile);
                }
                if (e instanceof BackupException be) throw be;
                throw new BackupException(
                        "Lecture du fichier importé impossible après validation : " + e.getMessage(),
                        BackupException.Reason.EXECUTION_FAILED);
            } finally {
                if (tempPlainForValidation != null) {
                    Files.deleteIfExists(tempPlainForValidation);
                }
            }

            BackupRun run = new BackupRun();
            run.setTriggerSource(BackupRun.TRIGGER_IMPORTED);
            run.setStatus(BackupRun.STATUS_SUCCESS);
            run.setStartedAt(OffsetDateTime.now());
            run.setFinishedAt(OffsetDateTime.now());
            run.setFileName(fileName);
            run.setFileSizeBytes(size);
            run.setChecksumSha256(checksum);
            run.setEncrypted(encrypted);
            run.setTriggeredBy(username);
            run.setLabel(normalizeLabel("Import : " + abbreviate(sanitizeOriginalName(file), 150)));
            run = backupRunRepository.save(run);

            log.info("Backup imported: file={} originalName={} sizeBytes={} encrypted={} by={}",
                    fileName, file.getOriginalFilename(), size, encrypted, username);
            authAuditService.recordBackupImported(username, fileName, encrypted);
            return run;
        } catch (IOException e) {
            throw new BackupException(
                    "Erreur lors de l'import : " + e.getMessage(), BackupException.Reason.EXECUTION_FAILED);
        } finally {
            operationInProgress.set(false);
        }
    }

    /** Valide le format d'un dump {@code pg_restore --format=custom} sans rien modifier en base. */
    private void validateDumpFormat(Path file) throws BackupException {
        assertToolAvailable(pgRestorePath, "pg_restore");
        try {
            runProcess(List.of(pgRestorePath, "--list", file.toString()), Map.of(), "pg_restore --list");
        } catch (BackupException e) {
            throw new BackupException(
                    "Le fichier importé ne semble pas être une sauvegarde pg_dump valide "
                            + "(format --format=custom attendu) : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    private static String sanitizeOriginalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) return "fichier importé";
        // Retire tout chemin (le navigateur peut envoyer un chemin complet sur certains OS).
        String base = name.replaceAll("^.*[/\\\\]", "");
        return base.isBlank() ? "fichier importé" : base;
    }

    // -------------------------------------------------------
    // Retention — supprime le fichier, jamais la ligne d'historique
    // -------------------------------------------------------

    private void pruneOldBackups(Path storageDir) {
        int retentionCount = configurationService.effectiveSettings().retentionCount();
        List<BackupRun> successfulRuns = backupRunRepository.findByStatusOrderByStartedAtDesc(BackupRun.STATUS_SUCCESS);
        if (successfulRuns.size() <= retentionCount) return;

        for (BackupRun run : successfulRuns.subList(retentionCount, successfulRuns.size())) {
            if (run.getFileName() == null) continue;
            try {
                boolean deleted = Files.deleteIfExists(storageDir.resolve(run.getFileName()));
                if (deleted) {
                    log.info("Backup pruned by retention policy: {}", run.getFileName());
                }
            } catch (IOException e) {
                log.warn("Failed to prune backup file {}: {}", run.getFileName(), e.getMessage());
            }
        }
    }

    // -------------------------------------------------------
    // Purge manuelle explicite de l'historique (audit 01/08/2026)
    // -------------------------------------------------------

    /**
     * Supprime definitivement les lignes {@link BackupRestore} puis
     * {@link BackupRun} strictement anterieures a {@code cutoff}, ainsi que
     * leurs fichiers {@code .dump} associes s'ils sont encore sur disque.
     *
     * <p>Distinct de {@link #pruneOldBackups} (retention automatique, qui ne
     * supprime que les fichiers, jamais l'historique) : c'est une action
     * volontaire de l'administrateur pour se debarrasser d'un historique
     * devenu inutilement long, demandee explicitement le 31/07/2026
     * ("avoir tout sans pouvoir sans debarasser, ce n'est pas top").</p>
     *
     * <p>Ordre de suppression important pour respecter les contraintes de
     * cle etrangere ({@code backup_restores.backup_run_id} et
     * {@code .safety_backup_run_id} referencent {@code backup_runs} sans
     * CASCADE) : les restaurations anterieures a la coupure sont supprimees
     * en premier, puis seules les sauvegardes qui ne sont plus referencees
     * par AUCUNE restauration restante (y compris une restauration plus
     * recente que la coupure) sont supprimees. Les lignes RUNNING ne sont
     * jamais purgees, meme anciennes — {@link #reconcileOrphanedOperations}
     * s'en charge normalement au demarrage.</p>
     */
    public PurgeResult purgeHistoryBefore(OffsetDateTime cutoff) {
        List<BackupRestore> restoresToDelete =
                backupRestoreRepository.findByStartedAtBeforeAndStatusNot(cutoff, BackupRestore.STATUS_RUNNING);
        for (BackupRestore restore : restoresToDelete) {
            backupRestoreRepository.delete(restore);
        }
        backupRestoreRepository.flush();

        List<BackupRun> candidateRuns =
                backupRunRepository.findByStartedAtBeforeAndStatusNot(cutoff, BackupRun.STATUS_RUNNING);
        Path storageDir = Path.of(storagePathStr);
        int runsDeleted = 0;
        for (BackupRun run : candidateRuns) {
            boolean stillReferenced = backupRestoreRepository
                    .existsByBackupRunIdOrSafetyBackupRunId(run.getId(), run.getId());
            if (stillReferenced) {
                continue;
            }
            if (run.getFileName() != null) {
                try {
                    Files.deleteIfExists(storageDir.resolve(run.getFileName()));
                } catch (IOException e) {
                    log.warn("Purge : suppression du fichier {} impossible : {}", run.getFileName(), e.getMessage());
                }
            }
            backupRunRepository.delete(run);
            runsDeleted++;
        }

        log.info("Backup history purged: cutoff={} runsDeleted={} restoresDeleted={}",
                cutoff, runsDeleted, restoresToDelete.size());
        authAuditService.recordBackupPurged(currentUsername(), cutoff, runsDeleted, restoresToDelete.size());
        return new PurgeResult(runsDeleted, restoresToDelete.size());
    }

    /** Resultat d'une purge manuelle de l'historique (audit 01/08/2026). */
    public record PurgeResult(int runsDeleted, int restoresDeleted) {}

    // -------------------------------------------------------
    // Suppression fine d'une seule sauvegarde (audit 01/08/2026)
    // -------------------------------------------------------

    /**
     * Supprime definitivement une seule ligne {@link BackupRun} (et son
     * fichier {@code .dump} associe s'il est encore sur disque).
     *
     * <p>Complement de {@link #purgeHistoryBefore} : la purge en masse "avant
     * telle date" reste utile pour le nettoyage global, mais manque de
     * granularite au quotidien ("c'est trop bourin le avant telle date",
     * demande utilisateur du 01/08/2026). Meme garde-fou que la purge en
     * masse — jamais de suppression d'une sauvegarde encore referencee par
     * une restauration conservee dans l'historique — plus un refus explicite
     * si la ligne est encore RUNNING (une operation en cours ne se supprime
     * pas manuellement, elle se termine ou est reconciliee au demarrage par
     * {@link #reconcileOrphanedOperations}).</p>
     */
    public void deleteRun(Long backupRunId) throws BackupException {
        BackupRun run = fetchRunForDeletion(backupRunId);

        boolean stillReferenced = backupRestoreRepository
                .existsByBackupRunIdOrSafetyBackupRunId(backupRunId, backupRunId);
        if (stillReferenced) {
            throw new BackupException(
                    "Cette sauvegarde est encore référencée par une restauration conservée dans "
                            + "l'historique et ne peut pas être supprimée seule (purgez l'historique, ou "
                            + "supprimez-la avec ses restaurations liées).",
                    BackupException.Reason.CONFLICT);
        }

        deleteRunAndFile(run);
        log.info("Backup run #{} ({}) deleted manually.", backupRunId, run.getFileName());
        authAuditService.recordBackupDeleted(currentUsername(), backupRunId, false);
    }

    /**
     * Restaurations encore liees a cette sauvegarde, comme source restauree
     * ou comme sauvegarde de securite pre-restauration (audit 01/08/2026).
     * Utilise par l'IHM pour lister precisement, avant confirmation, ce
     * qu'une suppression en cascade ({@link #deleteRunCascade}) effacerait.
     */
    public List<BackupRestore> findLinkedRestores(Long backupRunId) {
        return backupRestoreRepository.findByBackupRunIdOrSafetyBackupRunId(backupRunId, backupRunId);
    }

    /**
     * Variante de {@link #deleteRun} qui supprime aussi, au lieu de refuser,
     * les {@link BackupRestore} encore liees a cette sauvegarde (comme
     * source ou comme sauvegarde de securite) — demande explicite de
     * l'utilisateur (01/08/2026) : la purge en masse "avant telle date"
     * reste trop grossiere pour se debarrasser d'une sauvegarde precise
     * issue d'un test de restauration.
     *
     * <p>Ne touche jamais a un AUTRE {@link BackupRun} : si une restauration
     * supprimee ici referencait egalement un autre run (par exemple son
     * propre run source, si {@code backupRunId} correspond a sa sauvegarde
     * de securite), cet autre run n'est jamais supprime ni modifie — il
     * devient simplement moins reference, potentiellement supprimable
     * individuellement ensuite. Comportement volontairement previsible :
     * un seul niveau de cascade, jamais de propagation en chaine.</p>
     *
     * <p>Refuse (comme {@link #deleteRun}) si la sauvegarde ou l'une des
     * restaurations liees est encore RUNNING.</p>
     */
    public void deleteRunCascade(Long backupRunId) throws BackupException {
        BackupRun run = fetchRunForDeletion(backupRunId);

        List<BackupRestore> linked = backupRestoreRepository
                .findByBackupRunIdOrSafetyBackupRunId(backupRunId, backupRunId);
        for (BackupRestore restore : linked) {
            if (BackupRestore.STATUS_RUNNING.equals(restore.getStatus())) {
                throw new BackupException(
                        "Impossible de supprimer : une restauration liée à cette sauvegarde est encore "
                                + "en cours.",
                        BackupException.Reason.CONFLICT);
            }
        }
        for (BackupRestore restore : linked) {
            backupRestoreRepository.delete(restore);
        }
        if (!linked.isEmpty()) {
            backupRestoreRepository.flush();
        }

        deleteRunAndFile(run);
        log.info("Backup run #{} ({}) deleted with cascade, {} linked restore(s) removed.",
                backupRunId, run.getFileName(), linked.size());
        authAuditService.recordBackupDeleted(currentUsername(), backupRunId, true);
    }

    private BackupRun fetchRunForDeletion(Long backupRunId) throws BackupException {
        BackupRun run = backupRunRepository.findById(backupRunId)
                .orElseThrow(() -> new BackupException(
                        "Sauvegarde introuvable.", BackupException.Reason.FILE_NOT_FOUND));
        if (BackupRun.STATUS_RUNNING.equals(run.getStatus())) {
            throw new BackupException(
                    "Impossible de supprimer une sauvegarde encore en cours.",
                    BackupException.Reason.CONFLICT);
        }
        return run;
    }

    private void deleteRunAndFile(BackupRun run) {
        if (run.getFileName() != null) {
            try {
                Files.deleteIfExists(Path.of(storagePathStr).resolve(run.getFileName()));
            } catch (IOException e) {
                log.warn("Suppression du fichier {} impossible : {}", run.getFileName(), e.getMessage());
            }
        }
        backupRunRepository.delete(run);
    }

    // -------------------------------------------------------
    // Exécution des processus externes — pattern ScanService
    // -------------------------------------------------------

    /**
     * Tables exclues de la sauvegarde applicative (bug corrige le
     * 31/07/2026) : ce sont les tables qui pilotent le moteur de sauvegarde
     * lui-meme (dev.subnetory.backup.BackupExecutionService). Si elles sont
     * incluses dans le dump :
     * <ol>
     *   <li>chaque sauvegarde contient sa propre ligne backup_runs encore au
     *       statut RUNNING (le dump est pris avant la mise a jour finale du
     *       statut a la fin de {@link #doBackup}) — une restauration ulterieure
     *       de ce fichier affiche donc cette sauvegarde comme bloquee "En
     *       cours" pour toujours ;</li>
     *   <li>{@code pg_restore --clean} droppe et recree ces tables avec
     *       l'ancien contenu du dump PENDANT que l'application, toujours
     *       connectee a la meme base, est en train d'ecrire dans ces memes
     *       tables pour tracer l'operation de restauration en cours — la
     *       ligne fraichement inseree disparait sous les pieds de Hibernate,
     *       qui leve {@code StaleObjectStateException} a la sauvegarde finale
     *       (500 sur {@code POST /admin/backup/runs/{id}/restore}).</li>
     * </ol>
     * Ces tables sont exclues du dump : une restauration remet a l'etat
     * anterieur les donnees metier (contexts, sites, adresses...) sans jamais
     * toucher a l'historique des sauvegardes/restaurations, qui reste continu
     * et fiable quel que soit le nombre de restaurations effectuees.
     */
    static final List<String> BACKUP_METADATA_TABLES =
            List.of("backup_settings", "backup_runs", "backup_restores");

    /**
     * Sequences proprietaires des colonnes {@code id} BIGSERIAL de
     * {@code backup_runs}/{@code backup_restores} (bug corrige le
     * 31/07/2026, deuxieme partie : {@code --exclude-table} exclut la table
     * elle-meme mais pas sa sequence associee — {@code pg_dump} continue
     * d'emettre {@code DROP SEQUENCE IF EXISTS ...} dans le dump). A la
     * restauration, ce {@code DROP SEQUENCE} echoue avec
     * {@code cannot drop sequence ... because other objects depend on it}
     * puisque la table {@code backup_runs} (non touchee par cette
     * restauration, exclue du dump) reference toujours sa sequence via le
     * DEFAULT de sa colonne {@code id}. D'apres la documentation pg_dump,
     * {@code --exclude-table} filtre par nom sur toute relation (tables,
     * vues, sequences...), donc lister explicitement le nom de la sequence
     * suffit a l'exclure aussi. {@code backup_settings.id} est un
     * {@code BIGINT} fixe (pas de sequence, voir V16__create_backup_settings.sql).
     */
    static final List<String> BACKUP_METADATA_SEQUENCES =
            List.of("backup_runs_id_seq", "backup_restores_id_seq");

    private void runPgDump(DbConnectionInfo conn, Path targetFile) throws BackupException {
        assertToolAvailable(pgDumpPath, "pg_dump");
        runProcess(buildPgDumpCommand(conn, targetFile),
                Map.of("PGPASSWORD", dbPassword == null ? "" : dbPassword), "pg_dump");
    }

    /**
     * Construction pure de la commande pg_dump, sans exécution — extrait de
     * {@link #runPgDump} pour être testable sans binaire pg_dump réel ni
     * conteneur PostgreSQL (voir {@code BackupExecutionServiceTest},
     * régression du bug corrigé le 31/07/2026 : vérifie que les tables listées
     * dans {@link #BACKUP_METADATA_TABLES} et les séquences listées dans
     * {@link #BACKUP_METADATA_SEQUENCES} sont bien exclues, sans dépendre
     * d'un environnement où pg_dump/pg_restore sont installés — indisponibles
     * en CI, voir {@code AdminBackupControllerIT}).
     */
    List<String> buildPgDumpCommand(DbConnectionInfo conn, Path targetFile) {
        List<String> command = new java.util.ArrayList<>(List.of(
                pgDumpPath,
                "--format=custom",
                "--no-password",
                "--host=" + conn.host(),
                "--port=" + conn.port(),
                "--username=" + dbUser,
                "--dbname=" + conn.database()));
        for (String table : BACKUP_METADATA_TABLES) {
            command.add("--exclude-table=" + table);
        }
        for (String sequence : BACKUP_METADATA_SEQUENCES) {
            command.add("--exclude-table=" + sequence);
        }
        command.add("--file=" + targetFile);
        return command;
    }

    private void runPgRestore(DbConnectionInfo conn, Path sourceFile) throws BackupException {
        assertToolAvailable(pgRestorePath, "pg_restore");
        runProcess(buildPgRestoreCommand(conn, sourceFile),
                Map.of("PGPASSWORD", dbPassword == null ? "" : dbPassword), "pg_restore");
    }

    /**
     * Construction pure de la commande pg_restore, sans exécution — même
     * raison que {@link #buildPgDumpCommand} (testable sans binaire réel).
     *
     * <p>{@code --exit-on-error} (bug corrige le 31/07/2026, troisieme
     * partie) : par defaut, {@code pg_restore} n'arrete PAS l'operation a la
     * premiere erreur — il continue d'executer les instructions suivantes de
     * l'archive et se contente de signaler les erreurs a la fin (code de
     * sortie non nul). Consequence observee concretement : une restauration
     * echouee a cause du {@code DROP SEQUENCE backup_runs_id_seq} bloque
     * (voir {@link #BACKUP_METADATA_SEQUENCES}) a quand meme execute plus
     * loin dans l'archive un {@code SELECT pg_catalog.setval(...)} qui a
     * remis le compteur de la sequence a sa valeur au moment du dump —
     * desynchronisant silencieusement la sequence de la table reelle (plus
     * recente), et provoquant ensuite une {@code ConstraintViolationException}
     * ("duplicate key ... backup_runs_pkey") des la sauvegarde manuelle
     * suivante. Avec {@code --exit-on-error}, pg_restore s'arrete au premier
     * echec au lieu de continuer a executer une archive partiellement
     * incompatible — une restauration qui echoue reste franche, sans effet
     * de bord silencieux sur l'etat de la base.</p>
     *
     * <p>{@code --single-transaction} (audit du 02/08/2026, correctif ELEVEE) :
     * {@code --exit-on-error} arrete l'execution au premier echec, mais
     * n'annule pas a lui seul les instructions deja executees avant cet
     * echec — sans {@code --single-transaction}, chaque instruction de
     * l'archive s'execute dans sa propre transaction implicite et est donc
     * deja committee individuellement des son execution. Une restauration
     * qui echoue a mi-parcours (ex. contrainte violee, connexion coupee)
     * peut alors laisser la base dans un etat partiellement restaure :
     * certaines tables deja droppees/recreees avec le contenu du dump,
     * d'autres encore a l'etat anterieur — un etat incoherent, ni l'ancien
     * ni le nouveau. {@code --single-transaction} enveloppe l'integralite
     * de la restauration dans un unique {@code BEGIN}/{@code COMMIT} : en
     * cas d'echec, PostgreSQL annule tout (ROLLBACK), et la base reste
     * exactement dans l'etat ou elle etait avant la tentative de
     * restauration — jamais d'etat intermediaire incoherent, quel que soit
     * le point d'echec dans l'archive. Sans objet avec plusieurs jobs
     * paralleles ({@code --jobs}), mais cette commande n'en utilise pas.</p>
     */
    List<String> buildPgRestoreCommand(DbConnectionInfo conn, Path sourceFile) {
        return List.of(
                pgRestorePath,
                "--clean",
                "--if-exists",
                "--no-owner",
                "--no-password",
                "--exit-on-error",
                "--single-transaction",
                "--host=" + conn.host(),
                "--port=" + conn.port(),
                "--username=" + dbUser,
                "--dbname=" + conn.database(),
                sourceFile.toString());
    }

    private void assertToolAvailable(String path, String label) throws BackupException {
        try {
            Process probe = new ProcessBuilder(path, "--version")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            boolean finished = probe.waitFor(5, TimeUnit.SECONDS);
            if (!finished || probe.exitValue() != 0) {
                throw new BackupException(
                        label + " ne répond pas correctement. Vérifiez l'installation.",
                        BackupException.Reason.TOOL_NOT_AVAILABLE);
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException(
                    label + " n'est pas installé ou introuvable dans le PATH du conteneur.",
                    BackupException.Reason.TOOL_NOT_AVAILABLE);
        }
    }

    private void runProcess(List<String> command, Map<String, String> extraEnv, String toolLabel)
            throws BackupException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.environment().putAll(extraEnv);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new BackupException(
                    "Impossible de démarrer " + toolLabel + " : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }

        CompletableFuture<byte[]> stdoutFuture = readAllBytesAsync(process.getInputStream());
        CompletableFuture<byte[]> stderrFuture = readAllBytesAsync(process.getErrorStream());

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BackupException(
                        toolLabel + " a dépassé le délai de " + timeoutSeconds + "s.",
                        BackupException.Reason.TIMEOUT);
            }

            stdoutFuture.get(10, TimeUnit.SECONDS);
            byte[] stderr = stderrFuture.get(10, TimeUnit.SECONDS);

            if (process.exitValue() != 0) {
                String error = new String(stderr, java.nio.charset.StandardCharsets.UTF_8).trim();
                throw new BackupException(
                        toolLabel + " a échoué (code " + process.exitValue() + ")"
                                + (error.isEmpty() ? "" : " : " + abbreviate(error, 500)),
                        BackupException.Reason.EXECUTION_FAILED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BackupException(toolLabel + " interrompu.", BackupException.Reason.EXECUTION_FAILED);
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            throw new BackupException(
                    "Lecture de la sortie de " + toolLabel + " impossible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            process.destroyForcibly();
            throw new BackupException(
                    toolLabel + " : erreur inattendue : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    private static CompletableFuture<byte[]> readAllBytesAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return stream.readAllBytes();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    // -------------------------------------------------------
    // Chiffrement des fichiers de sauvegarde (audit 01/08/2026, backlog #13)
    // -------------------------------------------------------

    /**
     * En-tete identifiant un fichier de sauvegarde chiffre par Subnetory.
     * Format sur disque : MAGIC (8 octets) + sel PBKDF2 (16 octets) + IV GCM
     * (12 octets) + texte chiffre AES-256-GCM (tag d'authentification de
     * 16 octets inclus, ajoute automatiquement par {@link CipherOutputStream})
     * + HMAC-SHA256 (32 octets) calcule sur tout ce qui precede. Deux couches
     * d'authentification independantes, avec des cles derivees separement
     * (jamais la meme cle pour deux primitives) : le tag GCM protege le
     * texte chiffre lui-meme, le HMAC externe protege le fichier entier
     * (y compris l'en-tete/sel/IV) et permet un rejet rapide d'un fichier
     * corrompu ou d'une mauvaise cle avant meme de tenter un dechiffrement.
     */
    private static final byte[] ENCRYPTION_MAGIC = "SNBKENC1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int ENCRYPTION_SALT_LENGTH = 16;
    private static final int ENCRYPTION_IV_LENGTH = 12;
    private static final int ENCRYPTION_GCM_TAG_BITS = 128;
    private static final int ENCRYPTION_HMAC_LENGTH = 32;
    private static final int ENCRYPTION_PBKDF2_ITERATIONS = 210_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Suffixe ajoute au nom de fichier d'une sauvegarde chiffree (ex. {@code subnetory-20260801-020000.dump.enc}). */
    static final String ENCRYPTED_FILE_SUFFIX = ".enc";

    /** Expose a l'IHM/l'API si une cle de chiffrement des sauvegardes est configuree — jamais la cle elle-meme. */
    public boolean isEncryptionEnabled() {
        return encryptionKey != null && !encryptionKey.isBlank();
    }

    private record DerivedKeys(SecretKeySpec aesKey, SecretKeySpec hmacKey) {}

    /**
     * Derive une cle AES-256 et une cle HMAC-SHA256 distinctes a partir de
     * {@link #encryptionKey} et d'un sel propre a chaque fichier
     * (PBKDF2WithHmacSHA256, 210 000 iterations — recommandation OWASP 2023
     * pour PBKDF2-HMAC-SHA256). Le sel n'est pas secret (stocke en clair
     * dans l'en-tete du fichier), seule {@link #encryptionKey} l'est.
     */
    private DerivedKeys deriveKeys(byte[] salt) throws BackupException {
        try {
            PBEKeySpec spec = new PBEKeySpec(encryptionKey.toCharArray(), salt, ENCRYPTION_PBKDF2_ITERATIONS, 512);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyMaterial = factory.generateSecret(spec).getEncoded();
            SecretKeySpec aesKey = new SecretKeySpec(keyMaterial, 0, 32, "AES");
            SecretKeySpec hmacKey = new SecretKeySpec(keyMaterial, 32, 32, "HmacSHA256");
            return new DerivedKeys(aesKey, hmacKey);
        } catch (GeneralSecurityException e) {
            throw new BackupException(
                    "Dérivation de la clé de chiffrement des sauvegardes impossible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** {@code true} si les 8 premiers octets du fichier correspondent a {@link #ENCRYPTION_MAGIC}. */
    private boolean isEncryptedFile(Path file) throws BackupException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] header = in.readNBytes(ENCRYPTION_MAGIC.length);
            return Arrays.equals(header, ENCRYPTION_MAGIC);
        } catch (IOException e) {
            throw new BackupException(
                    "Lecture du fichier impossible : " + e.getMessage(), BackupException.Reason.EXECUTION_FAILED);
        }
    }

    /**
     * Chiffre {@code plainFile} vers {@code encryptedFile} en flux (jamais le
     * fichier en clair entier en memoire — les dumps peuvent atteindre
     * plusieurs centaines de Mo, cf. {@code subnetory.backup.import-max-size-bytes}).
     */
    private void encryptFile(Path plainFile, Path encryptedFile) throws BackupException {
        byte[] salt = randomBytes(ENCRYPTION_SALT_LENGTH);
        byte[] iv = randomBytes(ENCRYPTION_IV_LENGTH);
        DerivedKeys keys = deriveKeys(salt);

        try (InputStream in = Files.newInputStream(plainFile);
             OutputStream rawOut = Files.newOutputStream(
                     encryptedFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.hmacKey());

            rawOut.write(ENCRYPTION_MAGIC);
            mac.update(ENCRYPTION_MAGIC);
            rawOut.write(salt);
            mac.update(salt);
            rawOut.write(iv);
            mac.update(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.aesKey(), new GCMParameterSpec(ENCRYPTION_GCM_TAG_BITS, iv));

            // MacUpdatingOutputStream ne ferme jamais rawOut a sa fermeture
            // (voir sa doc) : rawOut reste ouvert pour y ecrire le HMAC final
            // juste apres, une fois le CipherOutputStream (et son bloc GCM
            // final) entierement vide dans macOut.
            MacUpdatingOutputStream macOut = new MacUpdatingOutputStream(rawOut, mac);
            try (CipherOutputStream cipherOut = new CipherOutputStream(macOut, cipher)) {
                in.transferTo(cipherOut);
            }

            rawOut.write(mac.doFinal());
        } catch (Exception e) {
            throw new BackupException(
                    "Chiffrement du fichier de sauvegarde impossible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    /**
     * Dechiffre {@code encryptedFile} vers {@code plainFile}, en deux passes
     * en flux sur le fichier source (jamais le texte chiffre entier en
     * memoire) : la premiere verifie le HMAC sur l'integralite du fichier
     * (en-tete + texte chiffre) AVANT toute tentative de dechiffrement —
     * rejette immediatement un fichier altere ou une mauvaise cle, sans
     * exposer le moindre octet en clair issu d'un fichier non authentifie.
     * La seconde dechiffre effectivement via AES/GCM (dont le tag, inclus
     * dans le texte chiffre, est verifie automatiquement par
     * {@link CipherInputStream} — deuxieme couche d'authentification
     * independante du HMAC externe).
     */
    private void decryptFile(Path encryptedFile, Path plainFile) throws BackupException {
        long totalSize;
        try {
            totalSize = Files.size(encryptedFile);
        } catch (IOException e) {
            throw new BackupException(
                    "Lecture du fichier chiffré impossible : " + e.getMessage(), BackupException.Reason.EXECUTION_FAILED);
        }

        int headerLength = ENCRYPTION_MAGIC.length + ENCRYPTION_SALT_LENGTH + ENCRYPTION_IV_LENGTH;
        long ciphertextLength = totalSize - headerLength - ENCRYPTION_HMAC_LENGTH;
        if (ciphertextLength < 0) {
            throw new BackupException(
                    "Fichier de sauvegarde chiffré tronqué ou invalide (taille insuffisante).",
                    BackupException.Reason.EXECUTION_FAILED);
        }

        byte[] magic;
        byte[] salt;
        byte[] iv;
        try (InputStream headerIn = Files.newInputStream(encryptedFile)) {
            magic = headerIn.readNBytes(ENCRYPTION_MAGIC.length);
            salt = headerIn.readNBytes(ENCRYPTION_SALT_LENGTH);
            iv = headerIn.readNBytes(ENCRYPTION_IV_LENGTH);
        } catch (IOException e) {
            throw new BackupException(
                    "Lecture de l'en-tête du fichier chiffré impossible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
        if (!Arrays.equals(magic, ENCRYPTION_MAGIC)) {
            throw new BackupException(
                    "Ce fichier ne semble pas être une sauvegarde chiffrée par Subnetory (en-tête inattendu).",
                    BackupException.Reason.EXECUTION_FAILED);
        }

        DerivedKeys keys = deriveKeys(salt);

        // Passe 1 : verification du HMAC.
        try (InputStream in = Files.newInputStream(encryptedFile)) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.hmacKey());
            long toMac = headerLength + ciphertextLength;
            byte[] buffer = new byte[8192];
            long read = 0;
            int n;
            while (read < toMac
                    && (n = in.read(buffer, 0, (int) Math.min(buffer.length, toMac - read))) != -1) {
                mac.update(buffer, 0, n);
                read += n;
            }
            byte[] storedMac = in.readNBytes(ENCRYPTION_HMAC_LENGTH);
            byte[] computedMac = mac.doFinal();
            if (storedMac.length != ENCRYPTION_HMAC_LENGTH || !MessageDigest.isEqual(computedMac, storedMac)) {
                throw new BackupException(
                        "Empreinte HMAC invalide : le fichier chiffré a été modifié, est corrompu, ou la clé "
                                + "de chiffrement configurée ne correspond pas à celle utilisée pour le "
                                + "chiffrer. Déchiffrement refusé par prudence.",
                        BackupException.Reason.EXECUTION_FAILED);
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException(
                    "Vérification de l'intégrité du fichier chiffré impossible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }

        // Passe 2 : dechiffrement effectif, uniquement une fois l'authenticite
        // confirmee ci-dessus.
        try (InputStream rawIn = Files.newInputStream(encryptedFile)) {
            rawIn.skipNBytes(headerLength);
            InputStream bounded = new BoundedInputStream(rawIn, ciphertextLength);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.aesKey(), new GCMParameterSpec(ENCRYPTION_GCM_TAG_BITS, iv));

            try (CipherInputStream cipherIn = new CipherInputStream(bounded, cipher);
                 OutputStream out = Files.newOutputStream(
                         plainFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                cipherIn.transferTo(out);
            } catch (IOException e) {
                // CipherInputStream (depuis JDK 9) enveloppe une AEADBadTagException
                // (echec du tag GCM) dans une IOException plutot que de la laisser
                // remonter telle quelle (read()/transferTo() ne declarent que
                // IOException) — cause() la retrouve pour un message explicite.
                if (e.getCause() instanceof AEADBadTagException) {
                    throw new BackupException(
                            "Authentification du chiffrement (tag GCM) invalide : fichier corrompu ou mauvaise clé.",
                            BackupException.Reason.EXECUTION_FAILED);
                }
                throw e;
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException(
                    "Déchiffrement du fichier de sauvegarde impossible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    /**
     * Flux d'ecriture qui alimente un {@link Mac} au fil de l'eau avant de
     * transmettre chaque octet au flux sous-jacent — permet de calculer le
     * HMAC du texte chiffre en une seule passe pendant son ecriture sur
     * disque par {@link CipherOutputStream}, sans le bufferiser entierement
     * en memoire. Ne ferme jamais volontairement le flux sous-jacent
     * ({@code out}) a sa propre fermeture : {@link #encryptFile} doit
     * pouvoir continuer d'ecrire le HMAC final juste apres.
     */
    private static final class MacUpdatingOutputStream extends FilterOutputStream {
        private final Mac mac;

        MacUpdatingOutputStream(OutputStream out, Mac mac) {
            super(out);
            this.mac = mac;
        }

        @Override
        public void write(int b) throws IOException {
            mac.update((byte) b);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            mac.update(b, off, len);
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    /** Flux de lecture borne a {@code limit} octets, pour ne jamais lire le HMAC final comme s'il faisait partie du texte chiffre. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b != -1) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(b, off, toRead);
            if (n > 0) remaining -= n;
            return n;
        }
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private Path ensureStorageDir() throws BackupException {
        try {
            Path dir = Path.of(storagePathStr);
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new BackupException(
                    "Impossible d'accéder au répertoire de sauvegarde " + storagePathStr + " : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    private DbConnectionInfo parseJdbcUrl(String url) throws BackupException {
        try {
            String stripped = url.startsWith("jdbc:") ? url.substring(5) : url;
            URI uri = URI.create(stripped);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath();
            String database = (path != null && path.startsWith("/")) ? path.substring(1) : path;
            if (host == null || database == null || database.isBlank()) {
                throw new IllegalArgumentException("host ou nom de base manquant");
            }
            return new DbConnectionInfo(host, port, database);
        } catch (Exception e) {
            throw new BackupException(
                    "URL de connexion à la base illisible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    private String sha256(Path file) throws BackupException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new BackupException(
                    "Calcul de l'empreinte SHA-256 impossible : " + e.getMessage(),
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    private void acquireOrThrow() throws BackupException {
        if (!operationInProgress.compareAndSet(false, true)) {
            throw new BackupException(
                    "Une opération de sauvegarde ou de restauration est déjà en cours. Réessayez dans quelques instants.",
                    BackupException.Reason.EXECUTION_FAILED);
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + "...";
    }

    /** {@code null}/vide -> {@code null} ; sinon coupe a 200 caracteres (taille de la colonne label). */
    private static String normalizeLabel(String label) {
        if (label == null) return null;
        String trimmed = label.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    /** Package-privé (au lieu de private) pour être instanciable depuis BackupExecutionServiceTest. */
    record DbConnectionInfo(String host, int port, String database) {}
}
