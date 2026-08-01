package dev.subnetory.service;

import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.repository.AuthAuditLogRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthAuditServiceCsvExportTest {

    @Test
    void exportAuditLogsCsv_escapesCsvAndNeutralizesFormulaInjection() {
        AuthAuditLogRepository repository = mock(AuthAuditLogRepository.class);
        AuthAuditService service = new AuthAuditService(repository);

        AuthAuditLog log = new AuthAuditLog();
        log.setEventType(AuthAuditService.LOGIN_SUCCESS);
        log.setUsername("=cmd");
        log.setTargetUsername("target,one");
        log.setIpAddress("127.0.0.1");
        log.setSuccess(true);
        log.setMessage("line1\nline2");
        log.setUserAgent("agent \"quoted\"");

        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(log));

        String csv = service.exportAuditLogsCsv(null, null);

        assertTrue(csv.startsWith("createdAt,eventType,username,targetUsername,ipAddress,success,message,userAgent"));
        assertTrue(csv.contains("'=cmd"));
        assertTrue(csv.contains("\"target,one\""));
        assertTrue(csv.contains("line1"));
        assertTrue(csv.contains("line2"));
        assertTrue(csv.contains("agent"));
        assertTrue(csv.contains("\"\""));
    }
}