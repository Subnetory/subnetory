package dev.subnetory.api.v1;

import com.opencsv.CSVWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.SubnetResponse;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.CsvSafeValue;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/subnets")
@Tag(name = "Subnets", description = "Sous-réseaux, exports et allocation d'adresses disponibles")
public class SubnetController {

    private static final String   XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] CSV_HEADER = {
            "network", "description", "gateway",
            "context_id", "context_name", "site_id", "site_name",
            "vlan_id", "vlan_name", "parent_id", "parent_network"
    };

    private final SubnetService subnetService;
    private final IpAllocService ipAllocService;
    private final AuthAuditService authAuditService;

    public SubnetController(SubnetService subnetService, IpAllocService ipAllocService,
                            AuthAuditService authAuditService) {
        this.subnetService = subnetService;
        this.ipAllocService = ipAllocService;
        this.authAuditService = authAuditService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Lister les sous-réseaux",
            description = "Limité aux contextes autorisés. Filtres optionnels siteId et "
                    + "contextId, tous deux contrôlés côté serveur. Tout rôle authentifié.")
    public Page<SubnetResponse> listSubnets(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            @PageableDefault(size = 20, sort = "network") Pageable pageable) {
        if (siteId != null) return subnetService.findBySite(siteId, pageable);
        if (contextId != null) return subnetService.findByContext(contextId, pageable);
        return subnetService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consulter un sous-réseau",
            description = "Répond 404 si le sous-réseau est hors du périmètre de contextes autorisés.")
    public ResponseEntity<SubnetResponse> getSubnet(@PathVariable Long id) {
        return ResponseEntity.ok(subnetService.findById(id));
    }

    /**
     * Exporte les subnets au format CSV.
     *
     * <p>Filtres optionnels : {@code siteId} et {@code contextId} (mutuellement exclusifs,
     * {@code siteId} prioritaire). Sans filtre, exporte tous les subnets.</p>
     *
     * <p>Header CSV :
     * {@code network,description,gateway,context_id,context_name,site_id,site_name,
     * vlan_id,vlan_name,parent_id,parent_network}</p>
     *
     * <p>Déclarée avant /{id}/scan et /{id}/available-ips — route littérale à deux segments.</p>
     */
    @GetMapping("/export/csv")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Exporter les sous-réseaux en CSV",
            description = "Export limité aux contextes autorisés, mêmes filtres que la liste. "
                    + "Valeurs neutralisées contre l'injection de formules tableur.")
    public void exportCsv(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"subnets_" + LocalDate.now() + ".csv\"");

        List<SubnetResponse> subnets = subnetService.findAllForExport(siteId, contextId);

        try (CSVWriter writer = new CSVWriter(
                response.getWriter(),
                CSVWriter.DEFAULT_SEPARATOR,
                CSVWriter.DEFAULT_QUOTE_CHARACTER,
                CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                CSVWriter.RFC4180_LINE_END)) {

            writer.writeNext(CSV_HEADER);

            for (SubnetResponse s : subnets) {
                writer.writeNext(CsvSafeValue.protectAll(new String[]{
                        s.network(),
                        s.description()    != null ? s.description()              : "",
                        s.gateway()        != null ? s.gateway()                  : "",
                        String.valueOf(s.contextId()),
                        s.contextName()    != null ? s.contextName()              : "",
                        String.valueOf(s.siteId()),
                        s.siteName()       != null ? s.siteName()                 : "",
                        s.vlanId()         != null ? String.valueOf(s.vlanId())   : "",
                        s.vlanName()       != null ? s.vlanName()                 : "",
                        s.parentId()       != null ? String.valueOf(s.parentId()) : "",
                        s.parentNetwork()  != null ? s.parentNetwork()            : ""
                }));
            }
        }
    }


    /**
     * Exporte les sous-réseaux au format Excel (.xlsx).
     *
     * <p>Filtres optionnels : {@code siteId} et {@code contextId} (mutuellement exclusifs,
     * {@code siteId} prioritaire). Sans filtre, exporte tous les subnets.</p>
     *
     * <p>Format : fichier mono-onglet "Sous-réseaux", header en gras sur fond bleu,
     * colonnes identiques au CSV export.</p>
     *
     * <p>Déclarée avant /{id} — route littérale prioritaire.</p>
     */
    @GetMapping("/export/xlsx")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Exporter les sous-réseaux en XLSX",
            description = "Export limité aux contextes autorisés, mêmes filtres que la liste.")
    public void exportXlsx(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            HttpServletResponse response) throws IOException {

        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"subnets_" + LocalDate.now() + ".xlsx\"");

        List<SubnetResponse> subnets = subnetService.findAllForExport(siteId, contextId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sous-réseaux");

            // Style header : gras, fond bleu Subnetory, texte blanc
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            // Ligne de header
            Row headerRow = sheet.createRow(0);
            String[] columns = CSV_HEADER;
            for (int i = 0; i < columns.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerStyle);
            }

            // Lignes de données
            int rowIdx = 1;
            for (SubnetResponse s : subnets) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(s.network());
                row.createCell(1).setCellValue(s.description()   != null ? s.description()              : "");
                row.createCell(2).setCellValue(s.gateway()       != null ? s.gateway()                  : "");
                row.createCell(3).setCellValue(s.contextId()     != null ? s.contextId().toString()     : "");
                row.createCell(4).setCellValue(s.contextName()   != null ? s.contextName()              : "");
                row.createCell(5).setCellValue(s.siteId()        != null ? s.siteId().toString()        : "");
                row.createCell(6).setCellValue(s.siteName()      != null ? s.siteName()                 : "");
                row.createCell(7).setCellValue(s.vlanId()        != null ? s.vlanId().toString()        : "");
                row.createCell(8).setCellValue(s.vlanName()      != null ? s.vlanName()                 : "");
                row.createCell(9).setCellValue(s.parentId()      != null ? s.parentId().toString()      : "");
                row.createCell(10).setCellValue(s.parentNetwork() != null ? s.parentNetwork()           : "");
            }

            // Auto-dimensionnement des colonnes après écriture de toutes les lignes
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un sous-réseau",
            description = "Rôles requis : ADMIN ou NETWORK. Le réseau CIDR doit être unique "
                    + "sur le site ; le contexte cible doit être dans le périmètre autorisé.")
    public SubnetResponse createSubnet(@Valid @RequestBody SubnetRequest request, Authentication auth) {
        SubnetResponse created = subnetService.create(request);
        authAuditService.recordSubnetCreated(auth.getName(), created.id(), created.network());
        return created;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    @Operation(summary = "Modifier un sous-réseau",
            description = "Rôles requis : ADMIN ou NETWORK. Changement de contexte, de site ou "
                    + "de réseau CIDR refusé si des adresses y sont encore rattachées ; "
                    + "changement de contexte ou de réseau CIDR refusé si des sous-réseaux "
                    + "enfants existent encore (409).")
    public ResponseEntity<SubnetResponse> updateSubnet(
            @PathVariable Long id,
            @Valid @RequestBody SubnetRequest request) {
        return ResponseEntity.ok(subnetService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un sous-réseau",
            description = "Rôle requis : ADMIN. Refusé si des adresses y sont encore "
                    + "rattachées (409).")
    public void deleteSubnet(@PathVariable Long id, Authentication auth) {
        SubnetResponse subnet = subnetService.findById(id);
        subnetService.delete(id);
        authAuditService.recordSubnetDeleted(auth.getName(), id, subnet.network());
    }

    /**
     * Retourne les premières IPs disponibles dans un sous-réseau.
     * Port de /api/ip/dispo/{subnet} du legacy Adrezo.
     *
     * @param id    identifiant du sous-réseau
     * @param count nombre d'IPs souhaitées (défaut: 5, max: 50)
     */
    @GetMapping("/{id}/available-ips")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Proposer des adresses IP disponibles",
            description = "Retourne jusqu'à 50 adresses libres du sous-réseau (défaut 5). "
                    + "Adresse réseau, broadcast et passerelle sont exclues. Tout rôle authentifié.")
    public ResponseEntity<AvailableIpResponse> getAvailableIps(
            @PathVariable Long id,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int count) {
        return ResponseEntity.ok(ipAllocService.findAvailableIps(id, count));
    }
}
