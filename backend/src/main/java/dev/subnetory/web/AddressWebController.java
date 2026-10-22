package dev.subnetory.web;

import com.opencsv.CSVWriter;
import dev.subnetory.dto.AddressRequest;
import dev.subnetory.dto.AddressResponse;
import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.dto.BulkUpsertRequest;
import dev.subnetory.dto.BulkUpsertResponse;
import dev.subnetory.dto.CsvImportResponse;
import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesGroupsResponse;
import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.dto.SeriesRowsRequest;
import dev.subnetory.dto.SeriesTemplateExpandRequest;
import dev.subnetory.dto.SeriesTemplateExpansionResponse;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.csv.CsvParseException;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.exception.SeriesConflictException;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.SeriesReservationService;
import dev.subnetory.service.SeriesTemplateService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.ImportFileValidator;
import dev.subnetory.util.CsvSafeValue;
import dev.subnetory.web.form.AddressForm;
import dev.subnetory.web.form.BulkReservationForm;
import dev.subnetory.web.form.BulkReservationRow;
import dev.subnetory.web.form.SeriesGroupForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final MessageSource messageSource;
    private final SeriesReservationService seriesReservationService;
    private final SeriesTemplateService seriesTemplateService;

    public AddressWebController(AddressService addressService,
                                SubnetService subnetService,
                                IpAllocService ipAllocService,
                                ObjectProvider<ActiveContextService> activeContextServiceProvider,
                                ImportFileValidator importFileValidator,
                                AuthAuditService authAuditService,
                                MessageSource messageSource,
                                SeriesReservationService seriesReservationService,
                                SeriesTemplateService seriesTemplateService) {
        this.addressService = addressService;
        this.subnetService  = subnetService;
        this.ipAllocService = ipAllocService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
        this.importFileValidator = importFileValidator;
        this.authAuditService = authAuditService;
        this.messageSource = messageSource;
        this.seriesReservationService = seriesReservationService;
        this.seriesTemplateService = seriesTemplateService;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
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
                       HttpSession session,
                       Locale locale) {
        var pageable  = PageRequest.of(page, PAGE_SIZE, Sort.by("address"));
        Long activeContextId = activeContextService == null ? null : activeContextService.get(session);
        var addresses = addressService.search(
                hostname, null, mac, q, null, activeContextId, subnetId, pageable);
        var pageNumbers = IntStream.range(0, Math.min(addresses.getTotalPages(), 10))
                .boxed().toList();
        model.addAttribute("addresses", addresses);
        model.addAttribute("returnTo", ReturnTo.currentUrl());
        model.addAttribute("addressPageNumbers", pageNumbers);
        model.addAttribute("query", q);
        model.addAttribute("hostnameFilter", hostname);
        model.addAttribute("macFilter", mac);
        model.addAttribute("selectedSubnetId", subnetId);
        boolean manageable = canManage(auth);
        boolean hasAvailableSubnets = false;
        if (manageable) {
            var availableSubnets = activeContextId == null
                    ? subnetService.findAll(Pageable.unpaged())
                    : subnetService.findByContext(activeContextId, Pageable.unpaged());
            hasAvailableSubnets = availableSubnets != null && !availableSubnets.isEmpty();
        }
        model.addAttribute("canManage", manageable);
        model.addAttribute("hasAvailableSubnets", hasAvailableSubnets);
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", msg("nav.addresses", locale));
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
            HttpSession session,
            Locale locale) throws IOException {

        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"addresses_" + LocalDate.now() + ".xlsx\"");

        List<AddressResponse> addresses = addressService.searchAll(
                hostname, null, mac, q, null,
                activeContextService == null ? null : activeContextService.get(session), subnetId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(msg("export.addresses.sheetName", locale));

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
 public String importPage(Model model, Authentication auth, Locale locale) {
     model.addAttribute("canManage", canManage(auth));
     model.addAttribute("activeSection", "addresses");
     model.addAttribute("pageTitle", msg("pageTitle.addressImport", locale));
     return "network/address-import";
 }

 @PostMapping("/import")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importAuto(@RequestParam("file") MultipartFile file,
                          @RequestParam(defaultValue = "false") boolean override,
                          Authentication auth,
                          RedirectAttributes flash,
                          HttpSession session,
                          Locale locale) {
     return importAddresses(file, override, auth, flash, detectImportFormat(file), session, locale);
 }

 @PostMapping("/import/csv")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importCsv(@RequestParam("file") MultipartFile file,
                         @RequestParam(defaultValue = "false") boolean override,
                         Authentication auth,
                         RedirectAttributes flash,
                         HttpSession session,
                         Locale locale) {
     return importAddresses(file, override, auth, flash, "csv", session, locale);
 }

 @PostMapping("/import/xlsx")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importXlsx(@RequestParam("file") MultipartFile file,
                          @RequestParam(defaultValue = "false") boolean override,
                          Authentication auth,
                          RedirectAttributes flash,
                          HttpSession session,
                          Locale locale) {
     return importAddresses(file, override, auth, flash, "xlsx", session, locale);
 }

 @GetMapping("/import-result")
 @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
 public String importResult(Model model,
                            Authentication auth,
                            RedirectAttributes flash,
                            Locale locale) {
     if (!model.containsAttribute("importResult")) {
         flash.addFlashAttribute("flashError", msg("flash.import.noReport", locale));
         return "redirect:/network/addresses/import";
     }

     model.addAttribute("canManage", canManage(auth));
     model.addAttribute("activeSection", "addresses");
     model.addAttribute("pageTitle", msg("pageTitle.importReport", locale));
     return "network/import-result";
 }

 private String importAddresses(MultipartFile file,
                                boolean override,
                                Authentication auth,
                                RedirectAttributes flash,
                                String format,
                                HttpSession session,
                                Locale locale) {
     if (format == null || format.isBlank()) {
         flash.addFlashAttribute("flashError", msg("flash.import.unknownFormat", locale));
         return "redirect:/network/addresses/import";
     }
     String label = format.toUpperCase();

     Long activeContextId = activeContextService == null ? null : activeContextService.get(session);
     if (activeContextId == null) {
         flash.addFlashAttribute("flashError", msg("flash.import.noActiveContext", locale));
         return "redirect:/network/addresses/import";
     }

     if (file == null || file.isEmpty()) {
         flash.addFlashAttribute("flashError", msg("flash.import.emptyFile", locale, label));
         return "redirect:/network/addresses/import";
     }

     try {
         byte[] content = importFileValidator.validate(file, format);
         CsvImportResponse result = "xlsx".equals(format)
                 ? addressService.importXlsx(new ByteArrayInputStream(content), override, auth.getName(), activeContextId)
                 : addressService.importCsv(new ByteArrayInputStream(content), override, auth.getName(), activeContextId);
         authAuditService.recordAddressImportCompleted(auth.getName(), format, result.totalRows(),
                 result.created(), result.updatedLastSeen(), result.skipped(), result.errors());

         flash.addFlashAttribute("importResult", result);
         flash.addFlashAttribute("importFormat", label);
         flash.addFlashAttribute("flashSuccess", msg("flash.import.success", locale, label));
         return "redirect:/network/addresses/import-result";
     } catch (CsvParseException e) {
         flash.addFlashAttribute("flashError", msg("flash.import.parseError", locale, label, e.getMessage()));
         return "redirect:/network/addresses/import";
     } catch (IOException e) {
         flash.addFlashAttribute("flashError", msg("flash.import.readError", locale, label));
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
    public String reserveForm(@RequestParam(required = false) Long subnetId, Model model, Locale locale) {
        BulkReservationForm form = new BulkReservationForm();
        form.setSubnetId(subnetId);
        prepareReserveModel(model, form, locale);
        return "network/address-reserve";
    }

    /** Génère (ou complète) le tableau de suggestions sans rien enregistrer. */
    @PostMapping("/reserve/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveGenerate(@Valid @ModelAttribute("form") BulkReservationForm form,
                                  BindingResult errors,
                                  Model model,
                                  Locale locale) {
        // Correctif securite/fonctionnel MOYENNE (02/08/2026) : @Valid
        // manquait ici alors qu'il est present sur reserveSubmit (soumission
        // finale) juste en dessous. Consequence concrete : le plafond
        // @Max(50) sur additionalCount (BulkReservationForm) n'etait
        // applique qu'a la toute derniere etape, jamais sur ce endpoint de
        // generation — un additionalCount hors bornes (ex. valeur negative
        // ou tres elevee forgee dans la requete) atteignait directement
        // appendSuggestedRows()/IpAllocService.findAvailableIps() sans
        // aucun garde-fou, avec un risque de balayage couteux d'un grand
        // sous-reseau.
        //
        // Le message "sous-reseau non selectionne" ci-dessous (message
        // dedie flash.reserve.selectSubnet) reste verifie EN PREMIER, avant
        // errors.hasErrors() : le template address-reserve.html n'affiche
        // qu'une seule bannière ${formError}, sans rendu des erreurs de
        // champ Bean Validation. Basculer directement sur errors.hasErrors()
        // pour ce cas précis (couvert aussi par @NotNull sur subnetId)
        // aurait renvoyé la page sans aucun message — une régression
        // silencieuse plutôt qu'une amélioration. Les autres violations
        // (ex. additionalCount hors bornes) restent couvertes par
        // errors.hasErrors() ci-dessous, avec le message deja traduit par
        // Spring (LocalValidatorFactoryBean resout {validation.*} via le
        // MessageSource applicatif).
        if (form.getSubnetId() == null) {
            model.addAttribute("formError", msg("flash.reserve.selectSubnet", locale));
            prepareReserveModel(model, form, locale);
            return "network/address-reserve";
        }
        if (errors.hasErrors()) {
            model.addAttribute("formError", errors.getFieldError() != null
                    ? errors.getFieldError().getDefaultMessage()
                    : msg("flash.reserve.generateFailed", locale));
            prepareReserveModel(model, form, locale);
            return "network/address-reserve";
        }
        try {
            appendSuggestedRows(form);
        } catch (ResourceNotFoundException e) {
            model.addAttribute("formError", msg("flash.reserve.subnetNotFound", locale));
        } catch (RuntimeException e) {
            model.addAttribute("formError", msg("flash.reserve.generateFailed", locale));
        }
        prepareReserveModel(model, form, locale);
        return "network/address-reserve";
    }

    /**
     * Calcule une série (adresses contiguës, noms incrémentés) et la charge dans le
     * tableau, sans rien enregistrer. Les lignes en conflit sont affichées avec leur
     * statut : l'adresse de chaque ligne peut être corrigée avant la création.
     */
    @PostMapping("/reserve/series")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveSeries(@ModelAttribute("form") BulkReservationForm form,
                                BindingResult errors,
                                Model model,
                                Locale locale) {
        form.getRows().clear();
        form.setAtomic(false);
        if (form.getSubnetId() == null) {
            model.addAttribute("formError", msg("flash.reserve.selectSubnet", locale));
            return reserveView(model, form, locale);
        }
        if (errors.hasErrors()) {
            model.addAttribute("formError", msg("flash.reserve.series.badNumber", locale));
            return reserveView(model, form, locale);
        }

        SeriesReservationRequest request = new SeriesReservationRequest(
                form.getSubnetId(),
                form.getSeriesStartIp() == null ? null : form.getSeriesStartIp().trim(),
                form.getSeriesCount(),
                form.getSeriesStep(),
                form.getSeriesPrefix(),
                form.getSeriesStartCounter(),
                form.getSeriesCounterWidth(),
                form.getSeriesSuffix(),
                form.isSeriesSkipOccupied() ? "skip" : "block",
                null,
                false);
        SeriesReservationResponse preview;
        try {
            preview = seriesReservationService.preview(request);
        } catch (InvalidSeriesException e) {
            model.addAttribute("formError", msg("flash.reserve.series.invalid", locale, e.getMessage()));
            return reserveView(model, form, locale);
        } catch (ResourceNotFoundException e) {
            model.addAttribute("formError", msg("flash.reserve.subnetNotFound", locale));
            return reserveView(model, form, locale);
        }

        String description = form.getSeriesDescription() == null || form.getSeriesDescription().isBlank()
                ? null : form.getSeriesDescription();
        for (SeriesReservationResponse.Entry entry : preview.rows()) {
            BulkReservationRow row = new BulkReservationRow(entry.ip());
            row.setHostname(entry.hostname());
            row.setDescription(description);
            row.setStatus(entry.status());
            form.getRows().add(row);
        }
        form.setAtomic(true);
        if (!preview.skipped().isEmpty()) {
            model.addAttribute("seriesSkipped", preview.skipped().stream()
                    .map(SeriesReservationResponse.Entry::ip)
                    .collect(java.util.stream.Collectors.joining(", ")));
        }
        if (preview.shortfall() > 0) {
            model.addAttribute("formError",
                    msg("flash.reserve.series.shortfall", locale, preview.shortfall()));
        } else if (!preview.canCreate()) {
            model.addAttribute("formError", msg("flash.reserve.atomic.conflict", locale));
        }
        return reserveView(model, form, locale);
    }

    /**
     * Calcule plusieurs séries enchaînées (ex. HOST, puis CVM, puis IPMI à partir d'une
     * même IP de départ) et les charge dans le tableau, sans rien enregistrer. Les lignes
     * sans nombre d'adresses sont ignorées ; la création finale passe par les lignes du
     * tableau (tout ou rien), comme pour une série simple.
     */
    @PostMapping("/reserve/groups")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveGroups(@ModelAttribute("form") BulkReservationForm form,
                                BindingResult errors,
                                Model model,
                                Locale locale) {
        form.getRows().clear();
        form.setAtomic(false);
        form.setGrouped(false);
        form.setMultiSubnet(false);
        if (form.getSubnetId() == null) {
            model.addAttribute("formError", msg("flash.reserve.selectSubnet", locale));
            return reserveView(model, form, locale);
        }
        if (errors.hasErrors()) {
            model.addAttribute("formError", msg("flash.reserve.series.badNumber", locale));
            return reserveView(model, form, locale);
        }

        List<SeriesGroupsRequest.Group> groups = form.getGroups().stream()
                .filter(g -> g != null && g.getCount() != null)
                .map(AddressWebController::toGroup)
                .toList();
        if (groups.isEmpty()) {
            model.addAttribute("formError", msg("flash.reserve.groups.none", locale));
            return reserveView(model, form, locale);
        }

        SeriesGroupsRequest request = new SeriesGroupsRequest(
                form.getSubnetId(),
                form.getSeriesStartIp() == null ? null : form.getSeriesStartIp().trim(),
                form.getSeriesGap(),
                form.isSeriesSkipOccupied() ? "skip" : "block",
                null,
                false,
                groups);
        SeriesGroupsResponse preview;
        try {
            preview = seriesReservationService.previewGroups(request);
        } catch (InvalidSeriesException e) {
            model.addAttribute("formError", msg("flash.reserve.series.invalid", locale, e.getMessage()));
            return reserveView(model, form, locale);
        } catch (ResourceNotFoundException e) {
            model.addAttribute("formError", msg("flash.reserve.subnetNotFound", locale));
            return reserveView(model, form, locale);
        }

        return showGroupsPreview(form, preview, model, locale);
    }

    /**
     * Applique un template (preset ou personnalisé) au sous-réseau choisi : calcule les séries
     * enchaînées (nombre de nœuds, nom du cluster, IP de départ) et charge le résultat dans le
     * tableau, sans rien enregistrer. La création finale passe par les lignes du tableau
     * (tout ou rien), comme pour les autres modes.
     */
    @PostMapping("/reserve/template")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveTemplate(@ModelAttribute("form") BulkReservationForm form,
                                  BindingResult errors,
                                  Model model,
                                  Locale locale) {
        form.getRows().clear();
        form.setAtomic(false);
        form.setGrouped(false);
        form.setMultiSubnet(false);
        if (form.getSubnetId() == null) {
            model.addAttribute("formError", msg("flash.reserve.selectSubnet", locale));
            return reserveView(model, form, locale);
        }
        if (errors.hasErrors()) {
            model.addAttribute("formError", msg("flash.reserve.series.badNumber", locale));
            return reserveView(model, form, locale);
        }
        if (form.getTemplateKey() == null || form.getTemplateKey().isBlank()) {
            model.addAttribute("formError", msg("flash.reserve.template.none", locale));
            return reserveView(model, form, locale);
        }

        String occupied = form.getTemplateOccupied() == null || form.getTemplateOccupied().isBlank()
                ? null : form.getTemplateOccupied();
        String description = form.getSeriesDescription() == null || form.getSeriesDescription().isBlank()
                ? null : form.getSeriesDescription();
        SeriesTemplateExpansionResponse expansion;
        try {
            expansion = seriesTemplateService.expand(new SeriesTemplateExpandRequest(
                    form.getSubnetId(),
                    form.getTemplateKey().trim(),
                    form.getTemplateNodes(),
                    form.getTemplateCluster(),
                    form.getTemplateSite(),
                    form.getTemplateStartIp() == null ? null : form.getTemplateStartIp().trim(),
                    form.getTemplateGap(),
                    form.getTemplateStep(),
                    occupied,
                    description,
                    false,
                    templateRoleTargets(form)));
        } catch (InvalidSeriesException e) {
            model.addAttribute("formError", msg("flash.reserve.series.invalid", locale, e.getMessage()));
            return reserveView(model, form, locale);
        } catch (ResourceNotFoundException e) {
            model.addAttribute("formError", msg("flash.reserve.template.notFound", locale));
            return reserveView(model, form, locale);
        }
        return showGroupsPreview(form, expansion.preview(), model, locale);
    }

    /** Contrôle les lignes du tableau (mode série) sans rien enregistrer. */
    @PostMapping("/reserve/check")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveCheck(@Valid @ModelAttribute("form") BulkReservationForm form,
                               BindingResult errors,
                               Model model,
                               Locale locale) {
        if (errors.hasErrors() || form.getSubnetId() == null || form.getRows().isEmpty()) {
            return reserveView(model, form, locale);
        }
        form.setAtomic(true);
        List<BulkReservationRow> selected = selectedRows(form);
        if (selected.isEmpty()) {
            model.addAttribute("formError", msg("flash.reserve.selectAtLeastOne", locale));
            return reserveView(model, form, locale);
        }
        try {
            SeriesReservationResponse result = seriesReservationService.previewRows(rowsRequest(form, selected));
            applyStatuses(form, selected, result);
            if (result.canCreate()) {
                model.addAttribute("formInfo", msg("flash.reserve.atomic.checkOk", locale, selected.size()));
            } else {
                model.addAttribute("formError", msg("flash.reserve.atomic.conflict", locale));
            }
        } catch (InvalidSeriesException e) {
            model.addAttribute("formError", msg("flash.reserve.series.invalid", locale, e.getMessage()));
        } catch (ResourceNotFoundException e) {
            model.addAttribute("formError", msg("flash.reserve.subnetNotFound", locale));
        }
        return reserveView(model, form, locale);
    }

    /** Soumission finale : seules les lignes cochées (`included`) sont réservées. */
    @PostMapping("/reserve")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String reserveSubmit(@Valid @ModelAttribute("form") BulkReservationForm form,
                                BindingResult errors,
                                Model model,
                                Authentication auth,
                                Locale locale) {
        if (errors.hasErrors()) {
            prepareReserveModel(model, form, locale);
            return "network/address-reserve";
        }

        if (form.isAtomic()) {
            return reserveSubmitAtomic(form, model, auth, locale);
        }

        List<BulkReservationRow> selected = form.getRows().stream()
                .filter(BulkReservationRow::isIncluded)
                .filter(r -> r.getAddress() != null && !r.getAddress().isBlank())
                .toList();

        if (selected.isEmpty()) {
            model.addAttribute("formError", msg("flash.reserve.selectAtLeastOne", locale));
            prepareReserveModel(model, form, locale);
            return "network/address-reserve";
        }

        List<BulkUpsertRequest.BulkUpsertEntry> entries = selected.stream()
                .map(r -> new BulkUpsertRequest.BulkUpsertEntry(
                        r.getAddress(), form.getSubnetId(), r.getMac(),
                        r.getHostname(), r.getDescription(), r.isTemporary(), "manual"))
                .toList();

        BulkUpsertResponse result = addressService.bulkUpsert(
                new BulkUpsertRequest(entries, false), auth.getName());
        authAuditService.recordAddressBulkUpserted(auth.getName(), result.created(), result.updated(),
                result.skipped(), result.errors().size());

        model.addAttribute("result", result);
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", msg("pageTitle.reservationResult", locale));
        return "network/address-reserve-result";
    }

    /**
     * Soumission finale d'une série : toutes les lignes cochées sont créées ensemble ou
     * pas du tout. En cas de conflit, le tableau est réaffiché avec le statut de chaque
     * ligne et rien n'est créé.
     */
    private String reserveSubmitAtomic(BulkReservationForm form, Model model,
                                       Authentication auth, Locale locale) {
        List<BulkReservationRow> selected = selectedRows(form);
        if (selected.isEmpty()) {
            model.addAttribute("formError", msg("flash.reserve.selectAtLeastOne", locale));
            return reserveView(model, form, locale);
        }
        try {
            SeriesReservationResponse result =
                    seriesReservationService.createRows(rowsRequest(form, selected), auth.getName());
            List<SeriesReservationResponse.Entry> rows = result.rows();
            // Une entrée d'audit par sous-réseau touché (les lignes peuvent en viser plusieurs).
            Map<Long, List<String>> ipsBySubnet = new LinkedHashMap<>();
            Map<Long, String> networks = new HashMap<>();
            for (int i = 0; i < selected.size(); i++) {
                Long subnetId = selected.get(i).getSubnetId() != null
                        ? selected.get(i).getSubnetId() : form.getSubnetId();
                ipsBySubnet.computeIfAbsent(subnetId, k -> new ArrayList<>()).add(rows.get(i).ip());
                networks.putIfAbsent(subnetId, subnetId.equals(form.getSubnetId())
                        ? result.network() : selected.get(i).getSubnetLabel());
            }
            ipsBySubnet.forEach((subnetId, ips) -> authAuditService.recordAddressSeriesReserved(
                    auth.getName(), subnetId, networks.get(subnetId),
                    ips.size(), ips.get(0), ips.get(ips.size() - 1)));
            model.addAttribute("result", new BulkUpsertResponse(rows.size(), 0, 0, List.of()));
            model.addAttribute("activeSection", "addresses");
            model.addAttribute("pageTitle", msg("pageTitle.reservationResult", locale));
            return "network/address-reserve-result";
        } catch (SeriesConflictException e) {
            applyStatuses(form, selected, e.getPreview());
            model.addAttribute("formError", msg("flash.reserve.atomic.conflict", locale));
        } catch (InvalidSeriesException e) {
            model.addAttribute("formError", msg("flash.reserve.series.invalid", locale, e.getMessage()));
        } catch (ConflictException e) {
            model.addAttribute("formError", msg("flash.reserve.atomic.concurrent", locale));
        } catch (ResourceNotFoundException e) {
            model.addAttribute("formError", msg("flash.reserve.subnetNotFound", locale));
        }
        return reserveView(model, form, locale);
    }

    /** Lignes cochées, adresse vide comprise (signalée INVALID plutôt qu'ignorée en silence). */
    private static List<BulkReservationRow> selectedRows(BulkReservationForm form) {
        return form.getRows().stream().filter(BulkReservationRow::isIncluded).toList();
    }

    private static SeriesRowsRequest rowsRequest(BulkReservationForm form, List<BulkReservationRow> selected) {
        return new SeriesRowsRequest(form.getSubnetId(), selected.stream()
                .map(r -> new SeriesRowsRequest.Row(
                        r.getAddress() == null ? "" : r.getAddress(),
                        r.getHostname(), r.getDescription(), r.getMac(), r.isTemporary(),
                        r.getSubnetId()))
                .toList());
    }

    /** Reporte les statuts du contrôle sur les lignes cochées (les autres sont remises à zéro). */
    private static void applyStatuses(BulkReservationForm form, List<BulkReservationRow> selected,
                                      SeriesReservationResponse result) {
        form.getRows().forEach(r -> r.setStatus(null));
        for (int i = 0; i < selected.size() && i < result.rows().size(); i++) {
            selected.get(i).setStatus(result.rows().get(i).status());
        }
    }

    /** Destinations par rôle saisies pour un template « un sous-réseau par rôle » (vide si aucune). */
    private static List<SeriesTemplateExpandRequest.RoleTarget> templateRoleTargets(BulkReservationForm form) {
        return form.getTemplateRoles().stream()
                .filter(r -> r != null && r.isFilled())
                .map(r -> new SeriesTemplateExpandRequest.RoleTarget(
                        r.getRole().trim(), r.getSubnetId(),
                        r.getStartIp() == null ? null : r.getStartIp().trim()))
                .toList();
    }

    private static SeriesGroupsRequest.Group toGroup(SeriesGroupForm g) {
        return new SeriesGroupsRequest.Group(
                g.getLabel() == null || g.getLabel().isBlank() ? null : g.getLabel().trim(),
                g.getCount(), null, g.getPrefix(), g.getStartCounter(), g.getCounterWidth(), g.getSuffix());
    }

    /**
     * Charge dans le tableau les lignes d'un aperçu de séries enchaînées (saisie manuelle des
     * groupes ou template) : IP, nom, groupe et statut de chaque ligne, sans rien enregistrer.
     */
    private String showGroupsPreview(BulkReservationForm form, SeriesGroupsResponse preview,
                                     Model model, Locale locale) {
        String description = form.getSeriesDescription() == null || form.getSeriesDescription().isBlank()
                ? null : form.getSeriesDescription();
        List<String> skipped = new java.util.ArrayList<>();
        int shortfall = 0;
        // Plusieurs sous-réseaux : chaque ligne garde le sien (colonne « sous-réseau » affichée).
        boolean multiSubnet = preview.groups().stream()
                .anyMatch(g -> g.subnetId() != null && !g.subnetId().equals(form.getSubnetId()));
        form.setMultiSubnet(multiSubnet);
        for (SeriesGroupsResponse.Group group : preview.groups()) {
            for (SeriesReservationResponse.Entry entry : group.rows()) {
                BulkReservationRow row = new BulkReservationRow(entry.ip());
                row.setHostname(entry.hostname());
                row.setDescription(description);
                row.setStatus(entry.status());
                row.setGroup(group.label());
                if (multiSubnet) {
                    row.setSubnetId(group.subnetId() != null ? group.subnetId() : form.getSubnetId());
                    row.setSubnetLabel(group.network() != null ? group.network() : preview.network());
                }
                form.getRows().add(row);
            }
            group.skipped().forEach(e -> skipped.add(e.ip()));
            shortfall += group.shortfall();
        }
        form.setAtomic(true);
        form.setGrouped(true);
        if (!skipped.isEmpty()) {
            model.addAttribute("seriesSkipped", String.join(", ", skipped));
        }
        if (shortfall > 0) {
            model.addAttribute("formError", msg("flash.reserve.series.shortfall", locale, shortfall));
        } else if (!preview.canCreate()) {
            model.addAttribute("formError", msg("flash.reserve.atomic.conflict", locale));
        }
        return reserveView(model, form, locale);
    }

    private String reserveView(Model model, BulkReservationForm form, Locale locale) {
        prepareReserveModel(model, form, locale);
        return "network/address-reserve";
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

    private void prepareReserveModel(Model model, BulkReservationForm form, Locale locale) {
        model.addAttribute("form", form);
        model.addAttribute("activeSection", "addresses");
        model.addAttribute("pageTitle", msg("pageTitle.bulkReserve", locale));
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        List<SeriesTemplateResponse> templates = seriesTemplateService.list(activeContextId);
        model.addAttribute("seriesTemplates", templates);
        model.addAttribute("hasCustomSeriesTemplates", templates.stream().anyMatch(t -> !t.builtin()));
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
    public String newForm(Model model, Locale locale) {
        prepareFormModel(model, new AddressForm(), msg("pageTitle.addressNew", locale),
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
                         RedirectAttributes flash,
                         Locale locale) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, msg("pageTitle.addressNew", locale),
                    "/network/addresses", "/network/addresses");
            return "network/address-form";
        }
        try {
            AddressResponse created = addressService.create(toRequest(form), auth.getName());
            authAuditService.recordAddressCreated(auth.getName(), created.id(), created.address());
            flash.addFlashAttribute("flashSuccess",
                    msg("flash.address.createSuccess", locale, form.getAddress()));
        } catch (ConflictException e) {
            model.addAttribute("formError", e.getMessage().contains("not in subnet")
                    ? msg("flash.address.notInSubnet", locale)
                    : msg("flash.address.alreadyRegistered", locale));
            prepareFormModel(model, form, msg("pageTitle.addressNew", locale),
                    "/network/addresses", "/network/addresses");
            return "network/address-form";
        }
        return ReturnTo.redirect("/network/addresses");
    }

    // ── Formulaire édition ─────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String editForm(@PathVariable Long id,
                           Model model,
                           HttpServletResponse response,
                           Locale locale) {
        try {
            prepareFormModel(model, AddressForm.from(addressService.findById(id)),
                    msg("pageTitle.addressEdit", locale),
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
                         HttpServletResponse response,
                         Locale locale) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, msg("pageTitle.addressEdit", locale),
                    "/network/addresses/" + id,
                    "/network/addresses/" + id);
            return "network/address-form";
        }
        try {
            AddressResponse updated = addressService.update(id, toRequest(form), auth.getName());
            authAuditService.recordAddressUpdated(auth.getName(), updated.id(), updated.address(), "WEB");
            flash.addFlashAttribute("flashSuccess", msg("flash.address.updateSuccess", locale));
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        } catch (ConflictException e) {
            model.addAttribute("formError", e.getMessage().contains("not in subnet")
                    ? msg("flash.address.notInSubnet", locale)
                    : msg("flash.address.alreadyRegisteredOther", locale));
            prepareFormModel(model, form, msg("pageTitle.addressEdit", locale),
                    "/network/addresses/" + id,
                    "/network/addresses/" + id);
            return "network/address-form";
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            // Verrouillage optimiste (audit du 31/07/2026) : quelqu'un d'autre
            // a modifié cette adresse entre le chargement du formulaire et
            // cette soumission.
            model.addAttribute("formError", msg("flash.address.optimisticLock", locale));
            prepareFormModel(model, form, msg("pageTitle.addressEdit", locale),
                    "/network/addresses/" + id,
                    "/network/addresses/" + id);
            return "network/address-form";
        }
        return ReturnTo.redirect("/network/addresses/" + id);
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes flash, Locale locale) {
        try {
            AddressResponse address = addressService.findById(id);
            addressService.delete(id);
            authAuditService.recordAddressDeleted(auth.getName(), id, address.address());
            flash.addFlashAttribute("flashSuccess", msg("flash.address.deleteSuccess", locale));
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.address.notFound", locale));
        }
        return ReturnTo.redirect("/network/addresses");
    }

    // ── Utilitaires privés ─────────────────────────────────────────────────

    private void prepareFormModel(Model model, AddressForm form,
                                  String pageTitle,
                                  String formAction,
                                  String cancelUrl) {
        model.addAttribute("form", form);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", ReturnTo.withReturnTo(formAction));
        model.addAttribute("cancelUrl", ReturnTo.cancelUrl(cancelUrl));
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
