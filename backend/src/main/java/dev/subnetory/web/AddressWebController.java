package dev.subnetory.web;

import com.opencsv.CSVWriter;
import dev.subnetory.dto.AddressRequest;
import dev.subnetory.dto.AddressResponse;
import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.dto.BulkUpsertRequest;
import dev.subnetory.dto.BulkUpsertResponse;
import dev.subnetory.dto.CsvImportResponse;
import dev.subnetory.csv.CsvParseException;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.ImportFileValidator;
import dev.subnetory.util.CsvSafeValue;
import dev.subnetory.web.form.AddressForm;
import dev.subnetory.web.form.BulkReservationForm;
import dev.subnetory.web.form.BulkReservationRow;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

@Controller
@RequestMapping("/network/addresses")
public class AddressWebController {

    private static final int    PAGE_SIZE         = 25;
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] EXPORT_HEADER = {
            "address", "subnet_id", "subnet_network", "mac",
            "hostname", "description", "temporary", "discovery_source"
    };

    private final AddressService addressService;
    private final SubnetService subnetService;
    private final IpAllocService ipAllocService;
    private final ActiveContextService activeContextService;
    private final ImportFileValidator importFileValidator;
    private final AuthAuditService authAuditService;

    public AddressWebController(AddressService addressService,
                                SubnetService subnetService,
                                IpAllocService ipAllocService,
                                ObjectProvider<ActiveContextService> activeContextServiceProvider,
                                ImportFileValidator importFileValidator,
                                AuthAuditService authAuditService) {
        this.addressService = addressService;
        this.subnetService  = subnetService;
        this.ipAllocService = ipAllocService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
        this.importFileValidator = importFileValidator;
        this.authAuditService = authAuditService;
    }

    // ── Liste ──────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String hostname,
                       @RequestParam(required = false) String mac,
                       @RequestParam(required = false) Long subnetId,
                       Authentication auth,
                       Model model,
                       HttpSession session) {
        var pageable  = PageRequest.of(page, PAGE_SIZE, Sort.by("address"));
        Long activeContextId = activeContextService == null ? null : activeContextService.get(session);
        var addresses = addressService.search(
                hostname, null, mac, q, null, activeContextId, subnetId, pageable);
        var pageNumbers = IntStream.range(0, Math.min(addresses.getTotalPages(), 10))
                .boxed().toList();
        model.addAttribute("addresses", addresses);
        model.addAttribute("addressPageNumbers", pageNumbers);
        model.addAttribute("query", q);
        model.addAttribute("hostnameFilter", hostname);
        model.addAttribute("macFilter", mac);
        model.addAttribute("selectedSubnetId", subnetId);
        model.addAttribute("canManage", canManage(auth));
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", "Adresses IP");
        return "network/addresses";
    }

    // ── Export CSV — session Web (Sprint 2.9) ──────────────────────────────

    /**
     * Exporte les adresses IP au format CSV depuis l'interface Web.
     *
     * <p>Protégé par la session Thymeleaf (chaîne @Order(3)) — accessible
     * depuis les boutons GUI sans token JWT.</p>
     *
     * <p>Transmet les filtres actifs de la page : l'export correspond exactement
     * à la vue courante de l'utilisateur.</p>
     *
     * <p>Format aligné sur {@code CSV_IMPORT_FORMAT.md} — round-trip garanti.</p>
     */
    @GetMapping("/export/csv")
    public void exportCsv(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String hostname,
            @RequestParam(required = false) String mac,
            @RequestParam(required = false) Long subnetId,
            HttpServletResponse response,
            HttpSession session) throws IOException {

        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"addresses_" + LocalDate.now() + ".csv\"");

        List<AddressResponse> addresses = addressService.searchAll(
                hostname, null, mac, q, null,
                activeContextService == null ? null : activeContextService.get(session), subnetId);

        try (CSVWriter writer = new CSVWriter(
                response.getWriter(),
                CSVWriter.DEFAULT_SEPARATOR,
                CSVWriter.DEFAULT_QUOTE_CHARACTER,
                CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                CSVWriter.RFC4180_LINE_END)) {

            writer.writeNext(EXPORT_HEADER);
            for (AddressResponse a : addresses) {
                writer.writeNext(CsvSafeValue.protectAll(new String[]{
                        a.address(),
                        String.valueOf(a.subnetId()),
                        a.subnetNetwork()   != null ? a.subnetNetwork()   : "",
                        a.mac()             != null ? a.mac()             : "",
                        a.hostname()        != null ? a.hostname()        : "",
                        a.description()     != null ? a.description()     : "",
                        String.valueOf(a.temporary()),
                        a.discoverySource() != null ? a.discoverySource() : "manual"
                }));
            }
        }
    }

    // ── Export XLSX — session Web (Sprint 2.9) ─────────────────────────────

    /**
     * Exporte les adresses IP au format Excel depuis l'interface Web.
     *
     * <p>Même logique que l'export CSV Web : protégé par la session,
     * filtres actifs transmis, colonnes identiques.</p>
     */
    @GetMapping("/export/xlsx")
    public void exportXlsx(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String hostname,
            @RequestParam(required = false) String mac,
            @RequestParam(required = false) Long subnetId,
            HttpServletResponse response,
            HttpSession session) throws IOException {

        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"addresses_" + LocalDate.now() + ".xlsx\"");

        List<AddressResponse> addresses = addressService.searchAll(
                hostname, null, mac, q, null,
                activeContextService == null ? null : activeContextService.get(session), subnetId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Adresses");

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADER.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(EXPORT_HEADER[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (AddressResponse a : addresses) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(a.address());
                row.createCell(1).setCellValue(a.subnetId()       != null ? a.subnetId().toString() : "");
                row.createCell(2).setCellValue(a.subnetNetwork()  != null ? a.subnetNetwork()        : "");
                row.createCell(3).setCellValue(a.mac()            != null ? a.mac()                  : "");
                row.createCell(4).setCellValue(a.hostname()       != null ? a.hostname()             : "");
                row.createCell(5).setCellValue(a.description()    != null ? a.description()          : "");
                row.createCell(6).setCellValue(String.valueOf(a.temporary()));
                row.createCell(7).setCellValue(a.discoverySource() != null ? a.discoverySource()     : "manual");
            }

            for (int i = 0; i < EXPORT_HEADER.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }

   
 // -- Import CSV/XLSX - session Web (Sprint 2.11) -----------------------

 @GetMapping("/import")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importPage(Model model, Authentication auth) {
     model.addAttribute("canManage", canManage(auth));
     model.addAttribute("activeSection", "addresses");
     model.addAttribute("pageTitle", "Importer des adresses");
     return "network/address-import";
 }

 @PostMapping("/import")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importAuto(@RequestParam("file") MultipartFile file,
                          @RequestParam(defaultValue = "false") boolean override,
                          Authentication auth,
                          RedirectAttributes flash,
                          HttpSession session) {
     return importAddresses(file, override, auth, flash, detectImportFormat(file), session);
 }

 @PostMapping("/import/csv")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importCsv(@RequestParam("file") MultipartFile file,
                         @RequestParam(defaultValue = "false") boolean override,
                         Authentication auth,
                         RedirectAttributes flash,
                         HttpSession session) {
     return importAddresses(file, override, auth, flash, "csv", session);
 }

 @PostMapping("/import/xlsx")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importXlsx(@RequestParam("file") MultipartFile file,
                          @RequestParam(defaultValue = "false") boolean override,
                          Authentication auth,
                          RedirectAttributes flash,
                          HttpSession session) {
     return importAddresses(file, override, auth, flash, "xlsx", session);
 }

 @GetMapping("/import-result")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importResult(Model model,
                            Authentication auth,
                            RedirectAttributes flash) {
     if (!model.containsAttribute("importResult")) {
         flash.addFlashAttribute("flashError", "Aucun rapport d'import disponible.");
         return "redirect:/network/addresses/import";
     }

     model.addAttribute("canManage", canManage(auth));
     model.addAttribute("activeSection", "addresses");
     model.addAttribute("pageTitle", "Rapport d'import");
     return "network/import-result";
 }

 private String importAddresses(MultipartFile file,
                                boolean override,
                                Authentication auth,
                                RedirectAttributes flash,
                                String format,
                                HttpSession session) {
     if (format == null || format.isBlank()) {
         flash.addFlashAttribute("flashError", "Format non reconnu. Importez un fichier .csv ou .xlsx.");
         return "redirect:/network/addresses/import";
     }
     String label = format.toUpperCase();

     Long activeContextId = activeContextService == null ? null : activeContextService.get(session);
     if (activeContextId == null) {
         flash.addFlashAttribute("flashError", "Sélectionnez un contexte actif avant d'importer.");
         return "redirect:/network/addresses/import";
     }

     if (file == null || file.isEmpty()) {
         flash.addFlashAttribute("flashError", "Fichier " + label + " vide.");
         return "redirect:/network/addresses/import";
     }

     try {
         byte[] content = importFileValidator.validate(file, format);
         CsvImportResponse result = "xlsx".equals(format)
                 ? addressService.importXlsx(new ByteArrayInputStream(content), override, auth.getName(), activeContextId)
                 : addressService.importCsv(new ByteArrayInputStream(content), override, auth.getName(), activeContextId);

         flash.addFlashAttribute("importResult", result);
         flash.addFlashAttribute("importFormat", label);
         flash.addFlashAttribute("flashSuccess", "Import " + label + " termine.");
         return "redirect:/network/addresses/import-result";
     } catch (CsvParseException e) {
         flash.addFlashAttribute("flashError", "Import " + label + " impossible : " + e.getMessage());
         return "redirect:/network/addresses/import";
     } catch (IOException e) {
         flash.addFlashAttribute("flashError", "Lecture du fichier " + label + " impossible.");
         return "redirect:/network/addresses/import";
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

 // ── Détail ─────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         HttpServletResponse response,
                         Authentication auth,
                         Model model) {
        AddressResponse addr;
        try {
            addr = addressService.findById(id);
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        model.addAttribute("address", addr);
        model.addAttribute("canManage", canManage(auth));
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", addr.address());
        return "network/address-detail";
    }

    // ── Réservation multiple ───────────────────────────────────────────────
    // Sprint : tableau de suggestions d'IP libres, éditable par lot, soumis
    // en une fois via AddressService.bulkUpsert() (même logique métier que
    // l'import CSV/XLSX et le endpoint API bulk-upsert — aucune règle dupliquée).

    @GetMapping("/reserve")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveForm(@RequestParam(required = false) Long subnetId, Model model) {
        BulkReservationForm form = new BulkReservationForm();
        form.setSubnetId(subnetId);
        prepareReserveModel(model, form);
        return "network/address-reserve";
    }

    /** Génère (ou complète) le tableau de suggestions sans rien enregistrer. */
    @PostMapping("/reserve/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveGenerate(@ModelAttribute("form") BulkReservationForm form, Model model) {
        if (form.getSubnetId() == null) {
            model.addAttribute("formError", "Sélectionnez un sous-réseau avant de générer des IP.");
        } else {
            try {
                appendSuggestedRows(form);
            } catch (ResourceNotFoundException e) {
                model.addAttribute("formError", "Sous-réseau introuvable.");
            } catch (RuntimeException e) {
                model.addAttribute("formError",
                        "Impossible de générer des IP disponibles pour ce sous-réseau.");
            }
        }
        prepareReserveModel(model, form);
        return "network/address-reserve";
    }

    /** Soumission finale : seules les lignes cochées (`included`) sont réservées. */
    @PostMapping("/reserve")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveSubmit(@Valid @ModelAttribute("form") BulkReservationForm form,
                                BindingResult errors,
                                Model model,
                                Authentication auth) {
        if (errors.hasErrors()) {
            prepareReserveModel(model, form);
            return "network/address-reserve";
        }

        List<BulkReservationRow> selected = form.getRows().stream()
                .filter(BulkReservationRow::isIncluded)
                .filter(r -> r.getAddress() != null && !r.getAddress().isBlank())
                .toList();

        if (selected.isEmpty()) {
            model.addAttribute("formError", "Sélectionnez au moins une ligne à réserver.");
            prepareReserveModel(model, form);
            return "network/address-reserve";
        }

        List<BulkUpsertRequest.BulkUpsertEntry> entries = selected.stream()
                .map(r -> new BulkUpsertRequest.BulkUpsertEntry(
                        r.getAddress(), form.getSubnetId(), r.getMac(),
                        r.getHostname(), r.getDescription(), r.isTemporary(), "manual"))
                .toList();

        BulkUpsertResponse result = addressService.bulkUpsert(
                new BulkUpsertRequest(entries, false), auth.getName());

        model.addAttribute("result", result);
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", "Résultat de la réservation");
        return "network/address-reserve-result";
    }

    /** Complète form.rows avec de nouvelles IP libres, sans dupliquer les adresses déjà listées. */
    private void appendSuggestedRows(BulkReservationForm form) {
        List<BulkReservationRow> existing = form.getRows();
        int totalWanted = existing.size() + form.getAdditionalCount();

        AvailableIpResponse response = ipAllocService.findAvailableIps(form.getSubnetId(), totalWanted);

        Set<String> already = new HashSet<>();
        for (BulkReservationRow row : existing) {
            already.add(row.getAddress());
        }

        int added = 0;
        for (String ip : response.availableIps()) {
            if (added >= form.getAdditionalCount()) break;
            if (!already.add(ip)) continue;
            existing.add(new BulkReservationRow(ip));
            added++;
        }
    }

    private void prepareReserveModel(Model model, BulkReservationForm form) {
        model.addAttribute("form", form);
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", "Réservation multiple d'adresses IP");
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        model.addAttribute("allSubnets", activeContextId == null
                ? subnetService.findAll(Pageable.unpaged())
                : subnetService.findByContext(activeContextId, Pageable.unpaged()));
        // Cette page est rendue directement (sans redirect) en reponse a des
        // POST sur /reserve/generate ou /reserve — l'URL du navigateur reste
        // donc sur un endpoint POST-only. On force ici le "returnTo" utilise
        // par le selecteur de contexte (layout/base.html) vers l'URL GET
        // stable de la page, pour eviter une redirection vers un chemin sans
        // handler GET (sinon 405 -> 500, cf. GlobalExceptionHandler).
        model.addAttribute("currentRequestPath", "/network/addresses/reserve");
    }

    // ── Formulaire création ────────────────────────────────────────────────

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String newForm(Model model) {
        prepareFormModel(model, new AddressForm(), "Nouvelle adresse IP",
                "/network/addresses", "/network/addresses");
        return "network/address-form";
    }

    // ── Soumission création ────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String create(@Valid @ModelAttribute("form") AddressForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Nouvelle adresse IP",
                    "/network/addresses", "/network/addresses");
            return "network/address-form";
        }
        try {
            AddressResponse created = addressService.create(toRequest(form), auth.getName());
            authAuditService.recordAddressCreated(auth.getName(), created.id(), created.address());
            flash.addFlashAttribute("flashSuccess",
                    "Adresse " + form.getAddress() + " créée avec succès.");
        } catch (ConflictException e) {
            model.addAttribute("formError", e.getMessage().contains("not in subnet")
                    ? "Cette adresse n'appartient pas au sous-réseau sélectionné."
                    : "Cette adresse IP est déjà enregistrée.");
            prepareFormModel(model, form, "Nouvelle adresse IP",
                    "/network/addresses", "/network/addresses");
            return "network/address-form";
        }
        return "redirect:/network/addresses";
    }

    // ── Formulaire édition ─────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String editForm(@PathVariable Long id,
                           Model model,
                           HttpServletResponse response) {
        try {
            prepareFormModel(model, AddressForm.from(addressService.findById(id)),
                    "Modifier l'adresse IP",
                    "/network/addresses/" + id,
                    "/network/addresses/" + id);
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "network/address-form";
    }

    // ── Soumission édition ─────────────────────────────────────────────────

    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") AddressForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash,
                         HttpServletResponse response) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, "Modifier l'adresse IP",
                    "/network/addresses/" + id,
                    "/network/addresses/" + id);
            return "network/address-form";
        }
        try {
            addressService.update(id, toRequest(form), auth.getName());
            flash.addFlashAttribute("flashSuccess", "Adresse mise à jour.");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        } catch (ConflictException e) {
            model.addAttribute("formError", e.getMessage().contains("not in subnet")
                    ? "Cette adresse n'appartient pas au sous-réseau sélectionné."
                    : "Cette adresse IP est déjà enregistrée sur une autre entrée.");
            prepareFormModel(model, form, "Modifier l'adresse IP",
                    "/network/addresses/" + id,
                    "/network/addresses/" + id);
            return "network/address-form";
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            // Verrouillage optimiste (audit du 31/07/2026) : quelqu'un d'autre
            // a modifié cette adresse entre le chargement du formulaire et
            // cette soumission.
            model.addAttribute("formError",
                    "Cette adresse a été modifiée entre-temps par quelqu'un d'autre. Rechargez la page et réappliquez vos changements.");
            prepareFormModel(model, form, "Modifier l'adresse IP",
                    "/network/addresses/" + id,
                    "/network/addresses/" + id);
            return "network/address-form";
        }
        return "redirect:/network/addresses/" + id;
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        try {
            AddressResponse address = addressService.findById(id);
            addressService.delete(id);
            authAuditService.recordAddressDeleted(auth.getName(), id, address.address());
            flash.addFlashAttribute("flashSuccess", "Adresse supprimée.");
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", "Adresse introuvable.");
        }
        return "redirect:/network/addresses";
    }

    // ── Utilitaires privés ─────────────────────────────────────────────────

    private void prepareFormModel(Model model, AddressForm form,
                                  String pageTitle,
                                  String formAction,
                                  String cancelUrl) {
        model.addAttribute("form", form);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", formAction);
        model.addAttribute("cancelUrl", cancelUrl);
        model.addAttribute("activeSection", "addresses");
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        model.addAttribute("allSubnets", activeContextId == null
                ? subnetService.findAll(Pageable.unpaged())
                : subnetService.findByContext(activeContextId, Pageable.unpaged()));
    }

    private AddressRequest toRequest(AddressForm form) {
        return new AddressRequest(
                form.getAddress(),
                form.getMac(),
                form.getHostname(),
                form.getDescription(),
                form.getSubnetId(),
                form.isTemporary(),
                null   // discoverySource : null => service defaulte à "manual"
        );
    }

    private boolean canManage(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                            || "ROLE_IP".equals(a.getAuthority()));
    }
}
