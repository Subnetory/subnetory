package dev.subnetory.web;

import com.opencsv.CSVWriter;
import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.SubnetResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.scan.ScanException;
import dev.subnetory.scan.ScanRequest;
import dev.subnetory.scan.ScanResponse;
import dev.subnetory.scan.ScanService;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.NetworkContextService;
import dev.subnetory.service.SiteService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.service.VlanService;
import dev.subnetory.util.IpUtils;
import dev.subnetory.util.CsvSafeValue;
import dev.subnetory.web.form.SubnetForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.ObjectProvider;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Controller web pour les sous-réseaux.
 * La liste est transformée en SubnetRowView avec champ scannable par ligne.
 * Le POST /scan appelle ScanService directement — pas l'API REST.
 * La réponse scan est un redirect avec message flash — pas de JSON injecté.
 *
 * Sprint 2.9 : ajout des endpoints export CSV et XLSX protégés par session.
 */
@Controller
@RequestMapping("/network/subnets")
public class SubnetWebController {

    private static final int    PAGE_SIZE         = 20;
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] EXPORT_HEADER = {
            "network", "description", "gateway",
            "context_id", "context_name", "site_id", "site_name",
            "vlan_id", "vlan_name", "parent_id", "parent_network"
    };

    private final SubnetService subnetService;
    private final IpAllocService ipAllocService;
    private final ScanService scanService;
    private final NetworkContextService contextService;
    private final SiteService siteService;
    private final VlanService vlanService;
    private final ActiveContextService activeContextService;
    private final AuthAuditService authAuditService;
    private final MessageSource messageSource;

    public SubnetWebController(SubnetService subnetService,
                                IpAllocService ipAllocService,
                                ScanService scanService,
                                NetworkContextService contextService,
                                SiteService siteService,
                                VlanService vlanService,
                                ObjectProvider<ActiveContextService> activeContextServiceProvider,
                                AuthAuditService authAuditService,
                                MessageSource messageSource) {
        this.subnetService  = subnetService;
        this.ipAllocService = ipAllocService;
        this.scanService    = scanService;
        this.contextService = contextService;
        this.siteService    = siteService;
        this.vlanService    = vlanService;
        this.activeContextService = activeContextServiceProvider.getIfAvailable();
        this.authAuditService = authAuditService;
        this.messageSource = messageSource;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    // ── Liste ──────────────────────────────────────────────────────────────

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page,
                       @RequestParam(required = false) Long contextId,
                       @RequestParam(required = false) Long siteId,
                       @RequestParam(required = false) Long vlanId,
                       Authentication auth,
                       Model model,
                       HttpSession session,
                       Locale locale) {
        var pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("network"));
        Long selectedContextId = activeContextService == null
                ? contextId : activeContextService.resolve(session, contextId);
        Page<SubnetResponse> subnets;
        if (vlanId != null) {
            // Navigation drill-down VLAN → subnets (audit du 31/07/2026).
            subnets = subnetService.findByVlan(vlanId, pageable);
        } else if (siteId != null) {
            var site = siteService.findById(siteId);
            if (selectedContextId != null && !selectedContextId.equals(site.contextId())) {
                throw new ResourceNotFoundException("Site", siteId);
            }
            subnets = subnetService.findBySite(siteId, pageable);
        } else if (selectedContextId != null) {
            subnets = subnetService.findByContext(selectedContextId, pageable);
        } else {
            subnets = subnetService.findAll(pageable);
        }

        var rows = subnets.map(s -> new SubnetRowView(
                s.id(),
                s.network(),
                s.description(),
                s.gateway(),
                s.vlanName(),
                s.siteName(),
                s.contextName(),
                isScannable(s.network())
        ));

        boolean canManage = canManage(auth);

        model.addAttribute("subnets", rows);
        model.addAttribute("contexts",
                contextService.findAll(PageRequest.of(0, 100, Sort.by("name"))));
        model.addAttribute("sites", selectedContextId == null
                ? siteService.findAll(PageRequest.of(0, 200, Sort.by("code")))
                : siteService.findByContext(selectedContextId,
                        PageRequest.of(0, 200, Sort.by("code"))));
        model.addAttribute("selectedContextId", selectedContextId);
        model.addAttribute("selectedSiteId", siteId);
        model.addAttribute("selectedVlanId", vlanId);
        model.addAttribute("canScan", canManage);
        model.addAttribute("canManage", canManage);
        model.addAttribute("activeSection", "subnets");
        model.addAttribute("pageTitle", msg("nav.subnets", locale));
        return "network/subnets";
    }

    // ── Export CSV — session Web (Sprint 2.9) ──────────────────────────────

    /**
     * Exporte les sous-réseaux au format CSV depuis l'interface Web.
     *
     * <p>Protégé par la session Thymeleaf (chaîne @Order(3)) — accessible
     * depuis les boutons GUI sans token JWT.</p>
     *
     * <p>Les filtres actifs de la page (siteId, contextId) sont transmis :
     * l'export correspond exactement à la vue courante.</p>
     */
    @GetMapping("/export/csv")
    public void exportCsv(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            HttpServletResponse response,
            HttpSession session) throws IOException {

        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"subnets_" + LocalDate.now() + ".csv\"");

        Long selectedContextId = activeContextService == null
                ? contextId : activeContextService.resolve(session, contextId);
        validateSiteWithinActiveContext(siteId, selectedContextId);
        List<SubnetResponse> subnets = subnetService.findAllForExport(siteId, selectedContextId);

        try (CSVWriter writer = new CSVWriter(
                response.getWriter(),
                CSVWriter.DEFAULT_SEPARATOR,
                CSVWriter.DEFAULT_QUOTE_CHARACTER,
                CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                CSVWriter.RFC4180_LINE_END)) {

            writer.writeNext(EXPORT_HEADER);
            for (SubnetResponse s : subnets) {
                writer.writeNext(CsvSafeValue.protectAll(new String[]{
                        s.network(),
                        s.description()   != null ? s.description()              : "",
                        s.gateway()       != null ? s.gateway()                  : "",
                        String.valueOf(s.contextId()),
                        s.contextName()   != null ? s.contextName()              : "",
                        String.valueOf(s.siteId()),
                        s.siteName()      != null ? s.siteName()                 : "",
                        s.vlanId()        != null ? String.valueOf(s.vlanId())   : "",
                        s.vlanName()      != null ? s.vlanName()                 : "",
                        s.parentId()      != null ? String.valueOf(s.parentId()) : "",
                        s.parentNetwork() != null ? s.parentNetwork()            : ""
                }));
            }
        }
    }

    // ── Export XLSX — session Web (Sprint 2.9) ─────────────────────────────

    /**
     * Exporte les sous-réseaux au format Excel depuis l'interface Web.
     *
     * <p>Même logique que l'export CSV Web : protégé par la session,
     * filtres actifs transmis, colonnes identiques.</p>
     */
    @GetMapping("/export/xlsx")
    public void exportXlsx(
            @RequestParam(required = false) Long siteId,
            @RequestParam(required = false) Long contextId,
            HttpServletResponse response,
            HttpSession session,
            Locale locale) throws IOException {

        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"subnets_" + LocalDate.now() + ".xlsx\"");

        Long selectedContextId = activeContextService == null
                ? contextId : activeContextService.resolve(session, contextId);
        validateSiteWithinActiveContext(siteId, selectedContextId);
        List<SubnetResponse> subnets = subnetService.findAllForExport(siteId, selectedContextId);

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(msg("export.subnets.sheetName", locale));

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

            for (int i = 0; i < EXPORT_HEADER.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(response.getOutputStream());
        }
    }


    // ── IP disponible — session Web (Sprint 2.10) ─────────────────────────

    /**
     * Retourne les premières IPs disponibles dans un sous-réseau depuis l'interface Web.
     *
     * <p>Endpoint protégé par la session Web (/network/**), afin d'être utilisable
     * depuis la GUI Thymeleaf sans token JWT Bearer.</p>
     */
    @GetMapping("/{id}/available-ips")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<?> getAvailableIps(
            @PathVariable Long id,
            @RequestParam(defaultValue = "5") @Min(1) @Max(50) int count) {
        try {
            return ResponseEntity.ok(ipAllocService.findAvailableIps(id, count));
        } catch (ResourceNotFoundException e) {
            // Endpoint AJAX (fetch avec Accept: application/json, cf. app.js —
            // suggestion d'IP dans address-form.html). Le scoping de
            // GlobalExceptionHandler sur dev.subnetory.api (audit du
            // 31/07/2026) ne couvre plus ce controleur web : on reconstruit
            // ici le meme ProblemDetail JSON qu'avant, pour ne pas renvoyer
            // la page HTML error/404 (WebExceptionHandler) a un appel fetch()
            // qui attend du JSON.
            org.springframework.http.ProblemDetail pd = org.springframework.http.ProblemDetail
                    .forStatusAndDetail(org.springframework.http.HttpStatus.NOT_FOUND, e.getMessage());
            pd.setTitle("Resource Not Found");
            pd.setProperty("resourceType", e.getResourceType());
            pd.setProperty("identifier", e.getIdentifier());
            pd.setProperty("timestamp", java.time.Instant.now());
            return ResponseEntity.status(404).body(pd);
        }
    }
    // ── Scan ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String scanForm(@PathVariable Long id,
                           @RequestParam(defaultValue = "false") boolean override,
                           @RequestParam(defaultValue = "true") boolean resolveDns,
                           @RequestParam(defaultValue = "true") boolean arpPing,
                           @RequestParam(defaultValue = "normal") String timing,
                           @RequestParam(required = false) String dnsServers,
                           Model model,
                           HttpServletResponse response,
                           Locale locale) {
        try {
            var subnet = subnetService.findById(id);
            prepareScanModel(model, subnet, override, resolveDns, arpPing, timing, dnsServers, locale);
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "network/subnet-scan";
    }

    /**
     * Scan à la demande depuis la GUI.
     * Appelle ScanService directement (pas l'API REST).
     * La page affiche le suivi et la synthèse pour éviter l'effet "scan bloqué".
     */
    @PostMapping("/{id}/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String scan(@PathVariable Long id,
                       @RequestParam(defaultValue = "false") boolean override,
                       @RequestParam(defaultValue = "false") boolean resolveDns,
                       @RequestParam(defaultValue = "false") boolean arpPing,
                       @RequestParam(defaultValue = "normal") String timing,
                       @RequestParam(required = false) String dnsServers,
                       Authentication auth,
                       Model model,
                       HttpServletResponse response,
                       Locale locale) {
        var request = new ScanRequest("nmap", override, resolveDns, arpPing, timing, dnsServers);
        SubnetResponse subnet;
        try {
            subnet = subnetService.findById(id);
            ScanResponse result = scanService.scan(id, request, auth.getName());
            prepareScanModel(model, subnet, override, resolveDns, arpPing, timing, dnsServers, locale);
            model.addAttribute("scanResult", result);
        } catch (ScanException e) {
            try {
                subnet = subnetService.findById(id);
                prepareScanModel(model, subnet, override, resolveDns, arpPing, timing, dnsServers, locale);
            } catch (ResourceNotFoundException missing) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return "error/404";
            }
            if (e.getReason() == ScanException.Reason.TOOL_NOT_AVAILABLE) {
                model.addAttribute("scanError", msg("scan.error.toolNotAvailable", locale));
            } else if (e.getReason() == ScanException.Reason.SUBNET_TOO_LARGE) {
                model.addAttribute("scanError", msg("scan.error.subnetTooLarge", locale));
            } else if (e.getReason() == ScanException.Reason.TIMEOUT) {
                model.addAttribute("scanError", msg("scan.error.timeout", locale));
            } else if (e.getReason() == ScanException.Reason.INVALID_OPTIONS) {
                model.addAttribute("scanError", msg("scan.error.invalidOptions", locale));
            } else {
                model.addAttribute("scanError", msg("scan.error.generic", locale, e.getMessage()));
            }
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "network/subnet-scan";
    }

    // ── Formulaire création ────────────────────────────────────────────────

    @GetMapping("/new")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String newForm(Model model, Locale locale) {
        prepareFormModel(model, new SubnetForm(), msg("pageTitle.subnetNew", locale),
                "/network/subnets", "/network/subnets");
        return "network/subnet-form";
    }

    // ── Soumission création ────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String create(@Valid @ModelAttribute("form") SubnetForm form,
                         BindingResult errors,
                         Model model,
                         Authentication auth,
                         RedirectAttributes flash,
                         Locale locale) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, msg("pageTitle.subnetNew", locale),
                    "/network/subnets", "/network/subnets");
            return "network/subnet-form";
        }
        try {
            SubnetResponse created = subnetService.create(toRequest(form));
            authAuditService.recordSubnetCreated(auth.getName(), created.id(), created.network());
            flash.addFlashAttribute("flashSuccess",
                    msg("flash.subnet.createSuccess", locale, form.getNetwork()));
        } catch (ConflictException e) {
            model.addAttribute("formError", msg("flash.subnet.conflict", locale));
            prepareFormModel(model, form, msg("pageTitle.subnetNew", locale),
                    "/network/subnets", "/network/subnets");
            return "network/subnet-form";
        }
        return "redirect:/network/subnets";
    }

    // ── Formulaire édition ─────────────────────────────────────────────────

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String editForm(@PathVariable Long id,
                           Model model,
                           HttpServletResponse response,
                           Locale locale) {
        try {
            prepareFormModel(model, SubnetForm.from(subnetService.findById(id)),
                    msg("pageTitle.subnetEdit", locale),
                    "/network/subnets/" + id,
                    "/network/subnets");
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "network/subnet-form";
    }

    // ── Soumission édition ─────────────────────────────────────────────────

    @PostMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") SubnetForm form,
                         BindingResult errors,
                         Model model,
                         RedirectAttributes flash,
                         HttpServletResponse response,
                         Locale locale) {
        if (errors.hasErrors()) {
            prepareFormModel(model, form, msg("pageTitle.subnetEdit", locale),
                    "/network/subnets/" + id, "/network/subnets");
            // Piege POST/redirect (audit du 31/07/2026), meme pattern que
            // AddressWebController.prepareReserveModel() : pas de @GetMapping
            // jumeau sur /network/subnets/{id}, seul /{id}/edit existe.
            model.addAttribute("currentRequestPath", "/network/subnets/" + id + "/edit");
            return "network/subnet-form";
        }
        try {
            subnetService.update(id, toRequest(form));
            flash.addFlashAttribute("flashSuccess", msg("flash.subnet.updateSuccess", locale));
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        } catch (ConflictException e) {
            model.addAttribute("formError", msg("flash.subnet.conflict", locale));
            prepareFormModel(model, form, msg("pageTitle.subnetEdit", locale),
                    "/network/subnets/" + id, "/network/subnets");
            model.addAttribute("currentRequestPath", "/network/subnets/" + id + "/edit");
            return "network/subnet-form";
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            // Verrouillage optimiste (audit du 31/07/2026) : quelqu'un d'autre
            // a modifié ce sous-réseau entre le chargement du formulaire et
            // cette soumission.
            model.addAttribute("formError", msg("flash.subnet.optimisticLock", locale));
            prepareFormModel(model, form, msg("pageTitle.subnetEdit", locale),
                    "/network/subnets/" + id, "/network/subnets");
            model.addAttribute("currentRequestPath", "/network/subnets/" + id + "/edit");
            return "network/subnet-form";
        }
        return "redirect:/network/subnets";
    }

    // ── Suppression ────────────────────────────────────────────────────────

    @PostMapping("/{id}/delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String delete(@PathVariable Long id, Authentication auth, RedirectAttributes flash, Locale locale) {
        try {
            SubnetResponse subnet = subnetService.findById(id);
            subnetService.delete(id);
            authAuditService.recordSubnetDeleted(auth.getName(), id, subnet.network());
            flash.addFlashAttribute("flashSuccess", msg("flash.subnet.deleteSuccess", locale));
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.subnet.notFound", locale));
        } catch (DataIntegrityViolationException e) {
            flash.addFlashAttribute("flashError", msg("flash.subnet.deleteConflict", locale));
        }
        return "redirect:/network/subnets";
    }

    // ── Utilitaires privés ─────────────────────────────────────────────────

    private void prepareFormModel(Model model, SubnetForm form,
                                  String pageTitle,
                                  String formAction,
                                  String cancelUrl) {
        model.addAttribute("form", form);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("formAction", formAction);
        model.addAttribute("cancelUrl", cancelUrl);
        model.addAttribute("activeSection", "subnets");
        Long activeContextId = activeContextService == null
                ? null : activeContextService.getCurrentRequestContext();
        if (form.getContextId() == null && activeContextId != null) form.setContextId(activeContextId);
        model.addAttribute("allContexts", activeContextId == null
                ? contextService.findAll(Pageable.unpaged())
                : java.util.List.of(contextService.findById(activeContextId)));
        model.addAttribute("allSites", activeContextId == null
                ? siteService.findAll(Pageable.unpaged())
                : siteService.findByContext(activeContextId, Pageable.unpaged()));
        model.addAttribute("allVlans", activeContextId == null
                ? vlanService.findAll(Pageable.unpaged())
                : vlanService.findByContext(activeContextId, Pageable.unpaged()));
        model.addAttribute("allSubnets", activeContextId == null
                ? subnetService.findAll(Pageable.unpaged())
                : subnetService.findByContext(activeContextId, Pageable.unpaged()));
    }

    private SubnetRequest toRequest(SubnetForm form) {
        return new SubnetRequest(
                form.getNetwork(),
                form.getDescription(),
                form.getGateway(),
                form.getContextId(),
                form.getSiteId(),
                form.getVlanId(),
                form.getParentId()
        );
    }

    private void validateSiteWithinActiveContext(Long siteId, Long contextId) {
        if (siteId == null || contextId == null) return;
        var site = siteService.findById(siteId);
        if (!contextId.equals(site.contextId())) {
            throw new ResourceNotFoundException("Site", siteId);
        }
    }

    private void prepareScanModel(Model model,
                                  SubnetResponse subnet,
                                  boolean override,
                                  boolean resolveDns,
                                  boolean arpPing,
                                  String timing,
                                  String dnsServers,
                                  Locale locale) {
        String safeTiming = switch (timing) {
            case "fast", "gentle" -> timing;
            default -> "normal";
        };
        model.addAttribute("subnet", subnet);
        model.addAttribute("override", override);
        model.addAttribute("resolveDns", resolveDns);
        model.addAttribute("arpPing", arpPing);
        model.addAttribute("timing", safeTiming);
        model.addAttribute("dnsServers", dnsServers == null ? "" : dnsServers.trim());
        model.addAttribute("commandPreview", scanService.commandPreview(
                subnet.network(),
                new ScanRequest("nmap", override, resolveDns, arpPing, safeTiming, dnsServers)));
        model.addAttribute("activeSection", "subnets");
        model.addAttribute("pageTitle", msg("pageTitle.subnetScanPrefix", locale) + " " + subnet.network());
    }

    private boolean canManage(Authentication authentication) {
        if (authentication == null) return false;
        return authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                            || "ROLE_NETWORK".equals(a.getAuthority()));
    }

    private static boolean isScannable(String network) {
        if (network == null) return false;
        int slash = network.lastIndexOf('/');
        if (slash < 0 || slash == network.length() - 1) return false;
        try {
            return Integer.parseInt(network.substring(slash + 1)) >= 24;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
