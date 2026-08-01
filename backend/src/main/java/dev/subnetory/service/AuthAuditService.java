package dev.subnetory.service;

import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.repository.AuthAuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service centralise d audit des evenements d authentification.
 *
 * Sprint 2.13 / T5.
 */
@Service
public class AuthAuditService {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "LOGIN_FAILURE";
    public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String ADMIN_PASSWORD_RESET = "ADMIN_PASSWORD_RESET";
    public static final String TOKEN_REVOKED = "TOKEN_REVOKED";
    public static final String TOKENS_INVALIDATED = "TOKENS_INVALIDATED";
    public static final String CONTEXT_ACCESS_DENIED = "CONTEXT_ACCESS_DENIED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_CONTEXTS_UPDATED = "USER_CONTEXTS_UPDATED";
    public static final String MFA_ENABLED = "MFA_ENABLED";
    public static final String MFA_DISABLED = "MFA_DISABLED";
    public static final String MFA_RECOVERY_CODES_REGENERATED = "MFA_RECOVERY_CODES_REGENERATED";
    public static final String MFA_CHALLENGE_FAILED = "MFA_CHALLENGE_FAILED";
    public static final String MFA_DISABLED_BY_ADMIN = "MFA_DISABLED_BY_ADMIN";

    /**
     * Traçabilité étendue (01/08/2026, backlog #27) : le journal d'audit ne
     * couvrait jusqu'ici que l'authentification et la gestion des comptes.
     * Ces constantes couvrent en plus les sauvegardes (qui déclenche quoi,
     * quand) et les créations/suppressions des ressources métier
     * principales. Pas de champ structuré dédié à la ressource affectée
     * dans {@link dev.subnetory.domain.AuthAuditLog} (schéma volontairement
     * inchangé) : les détails (id, valeur) sont encodés en texte libre dans
     * {@code message}, même convention que {@code TOKEN_REVOKED}
     * (" jti=...") ou {@code CONTEXT_ACCESS_DENIED} (" contextId=...").
     */
    public static final String BACKUP_TRIGGERED = "BACKUP_TRIGGERED";
    public static final String BACKUP_IMPORTED = "BACKUP_IMPORTED";
    public static final String BACKUP_RESTORED = "BACKUP_RESTORED";
    public static final String BACKUP_DELETED = "BACKUP_DELETED";
    public static final String BACKUP_PURGED = "BACKUP_PURGED";
    public static final String BACKUP_SETTINGS_UPDATED = "BACKUP_SETTINGS_UPDATED";
    public static final String ADDRESS_CREATED = "ADDRESS_CREATED";
    public static final String ADDRESS_DELETED = "ADDRESS_DELETED";
    public static final String VLAN_CREATED = "VLAN_CREATED";
    public static final String VLAN_DELETED = "VLAN_DELETED";
    public static final String CONTEXT_CREATED = "CONTEXT_CREATED";
    public static final String CONTEXT_DELETED = "CONTEXT_DELETED";
    public static final String SITE_CREATED = "SITE_CREATED";
    public static final String SITE_DELETED = "SITE_DELETED";
    public static final String SUBNET_CREATED = "SUBNET_CREATED";
    public static final String SUBNET_DELETED = "SUBNET_DELETED";

    private static final int MAX_USER_AGENT_LENGTH = 255;
    private static final int MAX_MESSAGE_LENGTH = 500;

    private final AuthAuditLogRepository repository;

    public AuthAuditService(AuthAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<AuthAuditLog> findAuditLogs(String query, String eventType, Pageable pageable) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasEventType = eventType != null && !eventType.isBlank();

        if (hasEventType) {
            return repository.findByEventType(eventType.trim(), pageable);
        }

        if (hasQuery) {
            String q = query.trim();
            return repository.findByUsernameContainingIgnoreCaseOrTargetUsernameContainingIgnoreCase(
                    q, q, pageable);
        }

        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public String exportAuditLogsCsv(String query, String eventType) {
        List<AuthAuditLog> logs = findAuditLogsForExport(query, eventType);
        StringBuilder csv = new StringBuilder();

        csv.append("createdAt,eventType,username,targetUsername,ipAddress,success,message,userAgent\r\n");

        for (AuthAuditLog log : logs) {
            appendCsvRow(csv, log);
        }

        return csv.toString();
    }

    private List<AuthAuditLog> findAuditLogsForExport(String query, String eventType) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasEventType = eventType != null && !eventType.isBlank();

        if (hasEventType) {
            return repository.findByEventTypeOrderByCreatedAtDesc(eventType.trim());
        }

        if (hasQuery) {
            String q = query.trim();
            return repository.findByUsernameContainingIgnoreCaseOrTargetUsernameContainingIgnoreCaseOrderByCreatedAtDesc(q, q);
        }

        return repository.findAllByOrderByCreatedAtDesc();
    }

    private void appendCsvRow(StringBuilder csv, AuthAuditLog log) {
        csv.append(csvValue(log.getCreatedAt() == null ? null : log.getCreatedAt().toString())).append(",");
        csv.append(csvValue(log.getEventType())).append(",");
        csv.append(csvValue(log.getUsername())).append(",");
        csv.append(csvValue(log.getTargetUsername())).append(",");
        csv.append(csvValue(log.getIpAddress())).append(",");
        csv.append(csvValue(Boolean.toString(log.isSuccess()))).append(",");
        csv.append(csvValue(log.getMessage())).append(",");
        csv.append(csvValue(log.getUserAgent())).append("\r\n");
    }

    private String csvValue(String value) {
        if (value == null) {
            value = "";
        }

        value = protectCsvInjection(value);
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private String protectCsvInjection(String value) {
        if (value.isEmpty()) {
            return value;
        }

        char first = value.charAt(0);
        if (first == 61 || first == 43 || first == 45 || first == 64 || first == 9 || first == 10 || first == 13) {
            return "\u0027" + value;
        }

        return value;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginSuccess(String username, String ipAddress, String userAgent) {
        record(LOGIN_SUCCESS, username, null, ipAddress, userAgent, true, "Connexion reussie.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(String username, String ipAddress, String userAgent, String message) {
        record(LOGIN_FAILURE, username, null, ipAddress, userAgent, false, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginLocked(String username, String ipAddress, String userAgent, String message) {
        record(LOGIN_LOCKED, username, null, ipAddress, userAgent, false, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPasswordChange(String username, String ipAddress, String userAgent) {
        record(PASSWORD_CHANGE, username, username, ipAddress, userAgent, true,
                "Mot de passe modifie par l'utilisateur.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAdminPasswordReset(String adminUsername,
                                         String targetUsername,
                                         String ipAddress,
                                         String userAgent) {
        record(ADMIN_PASSWORD_RESET, adminUsername, targetUsername, ipAddress, userAgent, true,
                "Mot de passe reinitialise par un administrateur.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTokenRevoked(String username,
                                   String ipAddress,
                                   String userAgent,
                                   String jti) {
        record(TOKEN_REVOKED, username, username, ipAddress, userAgent, true,
                "Token API revoque. jti=" + jti);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTokensInvalidated(String username,
                                        String targetUsername,
                                        String ipAddress,
                                        String userAgent,
                                        String reason) {
        record(TOKENS_INVALIDATED, username, targetUsername, ipAddress, userAgent, true,
                "Tokens API invalides. reason=" + reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordContextAccessDenied(String username, Long contextId) {
        record(CONTEXT_ACCESS_DENIED, username, null, null, null, false,
                "Acces refuse a un contexte ou une ressource associee. contextId=" + contextId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUserCreated(String adminUsername,
                                  String targetUsername,
                                  int roleCount,
                                  int contextCount) {
        record(USER_CREATED, adminUsername, targetUsername, null, null, true,
                "Compte utilisateur cree. roleCount=" + roleCount + ", contextCount=" + contextCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUserContextsUpdated(String adminUsername,
                                          String targetUsername,
                                          int contextCount) {
        record(USER_CONTEXTS_UPDATED, adminUsername, targetUsername, null, null, true,
                "Perimetre de contextes mis a jour. contextCount=" + contextCount);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMfaEnabled(String username, String ipAddress, String userAgent) {
        record(MFA_ENABLED, username, username, ipAddress, userAgent, true,
                "MFA active par l'utilisateur.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMfaDisabled(String username, String ipAddress, String userAgent) {
        record(MFA_DISABLED, username, username, ipAddress, userAgent, true,
                "MFA desactive par l'utilisateur.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMfaRecoveryCodesRegenerated(String username, String ipAddress, String userAgent) {
        record(MFA_RECOVERY_CODES_REGENERATED, username, username, ipAddress, userAgent, true,
                "Codes de recuperation MFA regeneres.");
    }

    /**
     * Sprint 2.37 / Lot 3 : echec du defi MFA au login (Web ou API), distinct
     * de {@code LOGIN_FAILURE} (qui concerne le premier facteur, mot de
     * passe) pour permettre l'analyse a posteriori des deux facteurs
     * separement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMfaChallengeFailed(String username, String ipAddress, String userAgent) {
        record(MFA_CHALLENGE_FAILED, username, null, ipAddress, userAgent, false,
                "Echec de la verification du code MFA (login).");
    }

    /**
     * Sprint 2.37 / Lot 4 : desactivation du MFA d'un compte par un
     * administrateur (anti-lockout), distincte de {@code MFA_DISABLED}
     * (desactivation en self-service par le titulaire du compte).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMfaDisabledByAdmin(String adminUsername, String targetUsername,
                                         String ipAddress, String userAgent) {
        record(MFA_DISABLED_BY_ADMIN, adminUsername, targetUsername, ipAddress, userAgent, true,
                "MFA desactive par un administrateur (anti-lockout).");
    }

    // -------------------------------------------------------
    // Sauvegardes (01/08/2026, backlog #27)
    // -------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBackupTriggered(String username, String triggerSource, boolean success, String message) {
        record(BACKUP_TRIGGERED, username, null, null, null, success,
                "Sauvegarde declenchee. triggerSource=" + triggerSource
                        + (message != null ? " — " + message : ""));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBackupImported(String username, String fileName, boolean encrypted) {
        record(BACKUP_IMPORTED, username, null, null, null, true,
                "Sauvegarde importee. fileName=" + fileName + ", encrypted=" + encrypted);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBackupRestored(String username, Long backupRunId, boolean success, String message) {
        record(BACKUP_RESTORED, username, null, null, null, success,
                "Restauration. backupRunId=" + backupRunId + (message != null ? " — " + message : ""));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBackupDeleted(String username, Long backupRunId, boolean cascade) {
        record(BACKUP_DELETED, username, null, null, null, true,
                "Sauvegarde supprimee. backupRunId=" + backupRunId + ", cascade=" + cascade);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBackupPurged(String username, java.time.OffsetDateTime cutoff, int runsDeleted, int restoresDeleted) {
        record(BACKUP_PURGED, username, null, null, null, true,
                "Historique purge. cutoff=" + cutoff + ", runsDeleted=" + runsDeleted
                        + ", restoresDeleted=" + restoresDeleted);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBackupSettingsUpdated(String username, String cronExpression, int retentionCount, boolean enabled) {
        record(BACKUP_SETTINGS_UPDATED, username, null, null, null, true,
                "Configuration mise a jour. enabled=" + enabled + ", cron=" + cronExpression
                        + ", retention=" + retentionCount);
    }

    // -------------------------------------------------------
    // Ressources metier — creation/suppression (01/08/2026, backlog #27)
    // -------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAddressCreated(String username, Long addressId, String address) {
        record(ADDRESS_CREATED, username, null, null, null, true,
                "Adresse IP creee. id=" + addressId + ", address=" + address);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAddressDeleted(String username, Long addressId, String address) {
        record(ADDRESS_DELETED, username, null, null, null, true,
                "Adresse IP supprimee. id=" + addressId + ", address=" + address);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVlanCreated(String username, Long vlanId, String label) {
        record(VLAN_CREATED, username, null, null, null, true,
                "VLAN cree. id=" + vlanId + ", label=" + label);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordVlanDeleted(String username, Long vlanId, String label) {
        record(VLAN_DELETED, username, null, null, null, true,
                "VLAN supprime. id=" + vlanId + ", label=" + label);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordContextCreated(String username, Long contextId, String name) {
        record(CONTEXT_CREATED, username, null, null, null, true,
                "Contexte cree. id=" + contextId + ", name=" + name);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordContextDeleted(String username, Long contextId, String name) {
        record(CONTEXT_DELETED, username, null, null, null, true,
                "Contexte supprime. id=" + contextId + ", name=" + name);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSiteCreated(String username, Long siteId, String name) {
        record(SITE_CREATED, username, null, null, null, true,
                "Site cree. id=" + siteId + ", name=" + name);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSiteDeleted(String username, Long siteId, String name) {
        record(SITE_DELETED, username, null, null, null, true,
                "Site supprime. id=" + siteId + ", name=" + name);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSubnetCreated(String username, Long subnetId, String network) {
        record(SUBNET_CREATED, username, null, null, null, true,
                "Sous-reseau cree. id=" + subnetId + ", network=" + network);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSubnetDeleted(String username, Long subnetId, String network) {
        record(SUBNET_DELETED, username, null, null, null, true,
                "Sous-reseau supprime. id=" + subnetId + ", network=" + network);
    }

    private void record(String eventType,
                        String username,
                        String targetUsername,
                        String ipAddress,
                        String userAgent,
                        boolean success,
                        String message) {
        AuthAuditLog log = new AuthAuditLog();
        log.setEventType(eventType);
        log.setUsername(trimToNull(username));
        log.setTargetUsername(trimToNull(targetUsername));
        log.setIpAddress(trimToNull(ipAddress));
        log.setUserAgent(truncate(trimToNull(userAgent), MAX_USER_AGENT_LENGTH));
        log.setSuccess(success);
        log.setMessage(truncate(trimToNull(message), MAX_MESSAGE_LENGTH));

        repository.save(log);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
