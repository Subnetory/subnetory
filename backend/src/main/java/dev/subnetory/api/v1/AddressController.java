package dev.subnetory.api.v1;

import com.opencsv.CSVWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import dev.subnetory.csv.CsvParseException;
import dev.subnetory.dto.*;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.util.ImportFileValidator;
import dev.subnetory.util.CsvSafeValue;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/addresses")
@Tag(name = "Addresses", description = "Recherche, gestion, import et export des adresses IP")
public class AddressController {

    private static final String   XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] CSV_HEADER = {
            "address", "subnet_id", "subnet_network", "mac",
            "hostname", "description", "temporary", "discovery_source"
    };

    private final AddressService addressService;
    private final ImportFileValidator importFileValidator;
    private final AuthAuditService authAuditService;

    public AddressController(AddressService addressService, ImportFileValidator importFileValidator,
                              AuthAuditService authAuditService) {
        this.addressService = addressService;
        this.importFileValidator = importFileValidator;
        this.authAuditService = authAuditService;
    }

    // -------------------------------------------------------
    // GET — Lecture
    // Note : les routes spécifiques (by-ip, by-hostname, bulk-upsert, export)
    // sont déclarées AVANT /{id} pour éviter tout conflit de routing.
    // Spring MVC résout les routes littérales avant les templates,
    // mais l'ordre explicite ici documente l'intention.
    // -------------------------------------------------------

    /**
     * Recherche multi-critères. Tous les paramètres sont optionnels et cumulables.
     * Le filtre hostname est partiel et insensible à la casse.
     * Ex: GET /api/v1/addresses?hostname=srv&siteId=1
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Rechercher les adresses IP",
            description = "Recherche paginée limitée aux contextes autorisés. Filtres "
                    + "combinables (texte libre, hostname, MAC, subnet, contexte, source, "
                    + "dates). Tout rôle authentifié.")
    public Page<AddressResponse> listAddresses(
            @RequestParam(required = false) String hostname,
            @RequestParam(required = false) String hostnameContains,
            @RequestParam(required = false) String mac,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            @RequestParam(required = false) Long subnetId,
            @PageableDefault(size = 50, sort = "address") Pageable pageable) {
        return addressService.search(
                hostname, hostnameContains, mac, q, siteId, contextId, subnetId, pageable);
    }

    /**
     * Recherche par IP exacte (sans préfixe CIDR dans le path).
     * Ex: GET /api/v1/addresses/by-ip/192.168.1.10
     * Déclarée avant /{id} — route littérale prioritaire.
     */
    @GetMapping("/by-ip/{ip}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consulter une adresse par IP",
            description = "Résolution dans les contextes autorisés uniquement. Répond 404 "
                    + "si l'adresse n'existe pas ou est hors périmètre.")
    public ResponseEntity<AddressResponse> getAddressByIp(@PathVariable String ip) {
        return ResponseEntity.ok(addressService.findByIp(ip));
    }

    /**
     * Recherche par hostname exact — retourne une liste (un hostname peut avoir plusieurs IPs).
     * Ex: GET /api/v1/addresses/by-hostname/dc01
     * Déclarée avant /{id} — route littérale prioritaire.
     */
    @GetMapping("/by-hostname/{hostname}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consulter les adresses d'un hostname",
            description = "Toutes les adresses portant ce hostname dans les contextes autorisés.")
    public List<AddressResponse> getAddressesByHostname(@PathVariable String hostname) {
        return addressService.findByHostname(hostname);
    }

    /**
     * Exporte les adresses IP au format CSV.
     *
     * <p>Les paramètres de filtre sont identiques à {@code GET /api/v1/addresses} — tous
     * optionnels et cumulables. Sans filtre, exporte toutes les adresses.</p>
     *
     * <p>Le format CSV est aligné sur {@code CSV_IMPORT_FORMAT.md} pour garantir
     * un cycle export → correction → import sans transformation.</p>
     *
     * <p>Header CSV : {@code address,subnet_id,subnet_network,mac,hostname,description,
     * temporary,discovery_source}</p>
     *
     * <p>Déclarée avant /{id} — route littérale prioritaire (deux segments : export/csv).</p>
     */
    @GetMapping("/export/csv")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Exporter les adresses en CSV",
            description = "Export limité aux contextes autorisés, mêmes filtres que la "
                    + "recherche. Réimportable tel quel. Valeurs neutralisées contre "
                    + "l'injection de formules tableur.")
    public void exportCsv(
            @RequestParam(required = false) String hostname,
            @RequestParam(required = false) String hostnameContains,
            @RequestParam(required = false) String mac,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            @RequestParam(required = false) Long subnetId,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"addresses_" + LocalDate.now() + ".csv\"");

        List<AddressResponse> addresses = addressService.searchAll(
                hostname, hostnameContains, mac, q, siteId, contextId, subnetId);

        try (CSVWriter writer = new CSVWriter(
                response.getWriter(),
                CSVWriter.DEFAULT_SEPARATOR,
                CSVWriter.DEFAULT_QUOTE_CHARACTER,
                CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                CSVWriter.RFC4180_LINE_END)) {

            writer.writeNext(CSV_HEADER);

            for (AddressResponse a : addresses) {
                writer.writeNext(CsvSafeValue.protectAll(new String[]{
                        a.address(),
                        String.valueOf(a.subnetId()),
                        a.subnetNetwork() != null ? a.subnetNetwork() : "",
                        a.mac()             != null ? a.mac()             : "",
                        a.hostname()        != null ? a.hostname()        : "",
                        a.description()     != null ? a.description()     : "",
                        String.valueOf(a.temporary()),
                        a.discoverySource() != null ? a.discoverySource() : "manual"
                }));
            }
        }
    }


    /**
     * Exporte les adresses IP au format Excel (.xlsx).
     *
     * <p>Les paramètres de filtre sont identiques à {@code GET /api/v1/addresses} — tous
     * optionnels et cumulables. Sans filtre, exporte toutes les adresses.</p>
     *
     * <p>Format : fichier mono-onglet "Adresses", header en gras sur fond bleu,
     * colonnes identiques au CSV export pour cohérence import/export.</p>
     *
     * <p>Déclarée avant /{id} — route littérale prioritaire (deux segments : export/xlsx).</p>
     */
    @GetMapping("/export/xlsx")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Exporter les adresses en XLSX",
            description = "Export limité aux contextes autorisés, mêmes filtres que la "
                    + "recherche. Réimportable tel quel.")
    public void exportXlsx(
            @RequestParam(required = false) String hostname,
            @RequestParam(required = false) String hostnameContains,
            @RequestParam(required = false) String mac,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            @RequestParam(required = false) Long subnetId,
            HttpServletResponse response) throws IOException {

        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"addresses_" + LocalDate.now() + ".xlsx\"");

        List<AddressResponse> addresses = addressService.searchAll(
                hostname, hostnameContains, mac, q, siteId, contextId, subnetId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Adresses");

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
            for (AddressResponse a : addresses) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(a.address());
                row.createCell(1).setCellValue(a.subnetId()       != null ? a.subnetId().toString()       : "");
                row.createCell(2).setCellValue(a.subnetNetwork()  != null ? a.subnetNetwork()             : "");
                row.createCell(3).setCellValue(a.mac()            != null ? a.mac()                       : "");
                row.createCell(4).setCellValue(a.hostname()       != null ? a.hostname()                  : "");
                row.createCell(5).setCellValue(a.description()    != null ? a.description()               : "");
                row.createCell(6).setCellValue(String.valueOf(a.temporary()));
                row.createCell(7).setCellValue(a.discoverySource() != null ? a.discoverySource()          : "manual");
            }

            // Auto-dimensionnement des colonnes après écriture de toutes les lignes
            for (int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }

    /** GET par ID numérique. Spring MVC ne matchera jamais "by-ip" ou "by-hostname" ici
     *  car {id} est contraint à Long par le service. */
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consulter une adresse",
            description = "Répond 404 si l'adresse est hors du périmètre de contextes autorisés.")
    public ResponseEntity<AddressResponse> getAddress(@PathVariable Long id) {
        return ResponseEntity.ok(addressService.findById(id));
    }

    // -------------------------------------------------------
    // POST — Création + Bulk
    // -------------------------------------------------------

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer une adresse IP",
            description = "Rôles requis : ADMIN ou IP. L'adresse doit appartenir au "
                    + "sous-réseau cible et être unique dans ce sous-réseau. Le contexte "
                    + "doit être dans le périmètre autorisé.")
    public AddressResponse createAddress(
            @Valid @RequestBody AddressRequest request,
            Authentication auth) {
        AddressResponse created = addressService.create(request, auth.getName());
        authAuditService.recordAddressCreated(auth.getName(), created.id(), created.address());
        return created;
    }

    /**
     * Bulk-upsert : injecte les résultats d'un scan réseau.
     * Déclarée avant /{id} — route littérale prioritaire.
     *
     * @param override si true, écrase les champs existants ; sinon, seul last_seen_at est mis à jour
     */
    @PostMapping("/bulk-upsert")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Créer ou mettre à jour des adresses en masse",
            description = "Rôles requis : ADMIN ou IP. Chaque entrée est traitée "
                    + "individuellement ; les erreurs sont rapportées ligne par ligne "
                    + "sans interrompre le lot. override contrôle l'écrasement des "
                    + "entrées existantes.")
    public BulkUpsertResponse bulkUpsert(
            @Valid @RequestBody BulkUpsertRequest request,
            Authentication auth) {
        return addressService.bulkUpsert(request, auth.getName());
    }

    // -------------------------------------------------------
    // PUT — Mise à jour complète + Upsert par IP
    // -------------------------------------------------------

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Modifier une adresse IP",
            description = "Rôles requis : ADMIN ou IP. Remplacement complet.")
    public ResponseEntity<AddressResponse> updateAddress(
            @PathVariable Long id,
            @Valid @RequestBody AddressRequest request,
            Authentication auth) {
        return ResponseEntity.ok(addressService.update(id, request, auth.getName()));
    }

    /**
     * Upsert par IP : crée l'entrée si elle n'existe pas, met à jour last_seen_at sinon.
     * Déclarée avant /{id} — route littérale prioritaire.
     *
     * <p>Le path ne doit pas contenir de CIDR : /by-ip/192.168.1.10 (pas /32).</p>
     *
     * @param override si true, écrase les champs existants sur une IP existante
     */
    @PutMapping("/by-ip/{ip}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Créer ou remplacer une adresse par IP",
            description = "Rôles requis : ADMIN ou IP. Crée l'adresse si absente (201), "
                    + "la remplace sinon (200). Pratique pour l'automatisation idempotente.")
    public ResponseEntity<AddressResponse> upsertByIp(
            @PathVariable String ip,
            @RequestParam(defaultValue = "false") boolean override,
            @Valid @RequestBody AddressUpsertRequest request,
            Authentication auth) {
        return ResponseEntity.ok(
                addressService.upsertByIp(ip, request, override, auth.getName()));
    }

    // -------------------------------------------------------
    // PATCH — Mise à jour partielle
    // Reçoit Map<String,Object> pour distinguer champ absent vs null
    // -------------------------------------------------------

    /**
     * Mise à jour partielle. Seuls les champs présents dans le body sont modifiés.
     * Un champ présent avec la valeur null vide le champ (si nullable).
     * Un champ absent du body n'est pas touché.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Modifier partiellement une adresse IP",
            description = "Rôles requis : ADMIN ou IP. Seuls les champs fournis sont modifiés.")
    public ResponseEntity<AddressResponse> patchAddress(
            @PathVariable Long id,
            @RequestBody Map<String, Object> fields,
            Authentication auth) {
        return ResponseEntity.ok(addressService.patch(id, fields, auth.getName()));
    }

    // -------------------------------------------------------
    // DELETE
    // -------------------------------------------------------

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer une adresse IP",
            description = "Rôles requis : ADMIN ou IP.")
    public void deleteAddress(@PathVariable Long id, Authentication auth) {
        AddressResponse address = addressService.findById(id);
        addressService.delete(id);
        authAuditService.recordAddressDeleted(auth.getName(), id, address.address());
    }

    // -------------------------------------------------------
    // Import CSV / XLSX
    // -------------------------------------------------------

    /**
     * Importe des adresses IP depuis un fichier CSV ou XLSX.
     *
     * <p>L'extension du fichier détermine le parseur utilisé. Le contexte est obligatoire
     * pour éviter toute ambiguïté lorsque plusieurs clients utilisent les mêmes plages IP.</p>
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Importer des adresses IP depuis un fichier CSV ou XLSX")
    public ResponseEntity<?> importAddresses(
            @RequestParam("file") MultipartFile file,
            @RequestParam Long contextId,
            @RequestParam(defaultValue = "false") boolean override,
            Authentication auth) {

        String format = detectImportFormat(file);
        if (format.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(org.springframework.http.ProblemDetail.forStatusAndDetail(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            "Import a .csv or .xlsx file"));
        }
        return importByFormat(file, override, contextId, auth, format);
    }

    /**
     * Importe des adresses IP depuis un fichier CSV.
     *
     * <p>Format CSV attendu (header obligatoire) :</p>
     * <pre>
     * address,subnet_id,subnet_network,mac,hostname,description,temporary,discovery_source
     * 192.168.1.10,3,,aa:bb:cc:dd:ee:ff,srv-web-01,Serveur web,false,csv
     * 192.168.1.20,,192.168.1.0/24,,printer-01,Imprimante,,
     * </pre>
     *
     * @param file     fichier CSV multipart (UTF-8)
     * @param override si true, écrase les champs des entrées existantes
     * @return rapport d'import avec statistiques et erreurs par ligne
     */
    @PostMapping(value = "/import/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Importer des adresses depuis un fichier CSV",
            description = "Rôles requis : ADMIN ou IP. contextId associe l'import à un "
                    + "contexte du périmètre autorisé. Préférer POST /import qui détecte "
                    + "le format automatiquement.")
    public ResponseEntity<?> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestParam(required = false) Long contextId,
            Authentication auth) {
        return importByFormat(file, override, contextId, auth, "csv");
    }

    /**
     * Importe des adresses IP depuis un fichier XLSX.
     *
     * @param file     fichier XLSX multipart
     * @param override si true, écrase les champs des entrées existantes
     * @return rapport d'import avec statistiques et erreurs par ligne
     */
    @PostMapping(value = "/import/xlsx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Importer des adresses depuis un fichier XLSX",
            description = "Rôles requis : ADMIN ou IP. contextId associe l'import à un "
                    + "contexte du périmètre autorisé. Préférer POST /import qui détecte "
                    + "le format automatiquement.")
    public ResponseEntity<?> importXlsx(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestParam(required = false) Long contextId,
            Authentication auth) {
        return importByFormat(file, override, contextId, auth, "xlsx");
    }

    private ResponseEntity<?> importByFormat(MultipartFile file,
                                             boolean override,
                                             Long contextId,
                                             Authentication auth,
                                             String format) {
        try {
            byte[] content = importFileValidator.validate(file, format);
            CsvImportResponse response = "xlsx".equals(format)
                    ? addressService.importXlsx(
                            new ByteArrayInputStream(content), override, auth.getName(), contextId)
                    : addressService.importCsv(
                            new ByteArrayInputStream(content), override, auth.getName(), contextId);
            return ResponseEntity.ok(response);
        } catch (CsvParseException e) {
            return ResponseEntity.badRequest()
                    .body(org.springframework.http.ProblemDetail.forStatusAndDetail(
                            org.springframework.http.HttpStatus.BAD_REQUEST, e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.internalServerError()
                    .body(org.springframework.http.ProblemDetail.forStatusAndDetail(
                            org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            "Failed to read uploaded file: " + e.getMessage()));
        }
    }

    private String detectImportFormat(MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null) {
            return "";
        }
        String name = file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).trim();
        if (name.endsWith(".csv")) return "csv";
        if (name.endsWith(".xlsx")) return "xlsx";
        return "";
    }

}
