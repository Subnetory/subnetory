package dev.subnetory.api.v1;

import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.dto.AuditPurgeRequest;
import dev.subnetory.dto.AuditPurgeResponse;
import dev.subnetory.dto.AuthAuditLogResponse;
import dev.subnetory.service.AuthAuditRetentionService;
import dev.subnetory.service.AuthAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - Audit", description = "Journal d'audit d'authentification")
public class AdminAuditController {

    private final AuthAuditService authAuditService;
    private final AuthAuditRetentionService retentionService;

    public AdminAuditController(AuthAuditService authAuditService, AuthAuditRetentionService retentionService) {
        this.authAuditService = authAuditService;
        this.retentionService = retentionService;
    }

    @PostMapping("/purge")
    @Operation(summary = "Purger définitivement le journal d'audit avant une date",
            description = "Complète la purge automatique planifiée (subnetory.audit.retention.days, "
                    + "90 jours par défaut) par une action manuelle immédiate. Supprime définitivement "
                    + "toutes les entrées strictement antérieures à beforeDate.")
    public AuditPurgeResponse purge(@RequestBody AuditPurgeRequest request) {
        var cutoff = request.beforeDate().atStartOfDay().atOffset(ZoneOffset.UTC);
        int deleted = retentionService.purgeOlderThan(cutoff);
        return new AuditPurgeResponse(deleted);
    }

    @GetMapping
    @Operation(summary = "Lister les événements d'audit")
    public Page<AuthAuditLogResponse> list(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) String eventType,
                                           @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return authAuditService.findAuditLogs(q, eventType, pageable).map(this::toResponse);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Exporter le journal d'audit en CSV")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String eventType) {
        String csv = authAuditService.exportAuditLogsCsv(q, eventType);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auth-audit-log.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private AuthAuditLogResponse toResponse(AuthAuditLog log) {
        return new AuthAuditLogResponse(
                log.getId(),
                log.getEventType(),
                log.getUsername(),
                log.getTargetUsername(),
                log.getIpAddress(),
                log.getUserAgent(),
                log.isSuccess(),
                log.getMessage(),
                log.getCreatedAt());
    }
}
