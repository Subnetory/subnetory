package dev.subnetory.scan;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.BulkUpsertRequest;
import dev.subnetory.dto.BulkUpsertResponse;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.IpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service de scan réseau à la demande.
 *
 * <p>Appelle Nmap comme outil externe via {@link ProcessBuilder} avec des
 * arguments fixes. Aucun paramètre utilisateur n'est jamais injecté dans
 * la commande — le CIDR scanné provient exclusivement de la base de données.</p>
 *
 * <h3>Flux d'exécution</h3>
 * <ol>
 *   <li>Validation : subnet ≤ /24 (254 hôtes max)</li>
 *   <li>Détection nmap disponible</li>
 *   <li>Exécution : {@code nmap -sn -R -oX - <cidr>}</li>
 *   <li>Lecture stdout/stderr en parallèle via {@link CompletableFuture}</li>
 *   <li>Timeout strict : échec propre si dépassé (pas de résultats partiels)</li>
 *   <li>Parse XML via {@link NmapXmlParser}</li>
 *   <li>Appel {@link AddressService#bulkUpsert} avec les résultats</li>
 *   <li>Retour {@link ScanResponse}</li>
 * </ol>
 *
 * <h3>Sécurité</h3>
 * <ul>
 *   <li>ProcessBuilder avec liste d'arguments — pas de shell, pas d'injection</li>
 *   <li>CIDR extrait de la DB, jamais d'une entrée utilisateur</li>
 *   <li>Entités externes XML désactivées dans NmapXmlParser (protection XXE)</li>
 *   <li>stdout et stderr lus en parallèle pour éviter les blocages de buffer</li>
 *   <li>Concurrence limitée, globalement et par utilisateur (correctif
 *       FAIBLE/MOYEN, audit 04/08/2026) — voir {@link #globalScanSemaphore}
 *       et {@link #activeScansByUser} ; sortie Nmap bornée en taille
 *       (voir {@link #maxOutputBytes})</li>
 * </ul>
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    /**
     * Longueur de préfixe minimale autorisée pour le scan synchrone.
     * /24 = 254 hôtes utilisables — limite raisonnable pour un scan synchrone.
     * Les subnets plus grands (/16, /23...) sont refusés avec 400.
     */
    static final int MIN_ALLOWED_PREFIX_LENGTH = 24;

    private final SubnetService subnetService;
    private final AddressService addressService;
    private final RestoreMaintenanceGate restoreMaintenanceGate;

    @Value("${subnetory.scan.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${subnetory.scan.nmap-path:nmap}")
    private String nmapPath;

    /**
     * Limite globale de scans Nmap simultanes (correctif securite FAIBLE/MOYEN,
     * audit 04/08/2026) : sans elle, rien n'empeche un ou plusieurs
     * utilisateurs de declencher un nombre illimite de processus {@code nmap}
     * concurrents, chacun consommant CPU/reseau sur l'hote applicatif.
     * Rejet immediat (429), pas d'attente en file — un scan est une action
     * interactive synchrone, faire attendre indefiniment la requete HTTP
     * derriere une file serait pire pour l'utilisateur qu'un echec net et
     * immediat.
     */
    @Value("${subnetory.scan.max-concurrent:3}")
    private int maxConcurrentScans;

    /**
     * Limite par utilisateur (correctif securite FAIBLE/MOYEN, audit
     * 04/08/2026) : complementaire a {@link #maxConcurrentScans} — sans elle,
     * un seul utilisateur pourrait a lui seul consommer tout le quota global,
     * empechant les autres de lancer le moindre scan.
     */
    @Value("${subnetory.scan.max-concurrent-per-user:1}")
    private int maxConcurrentScansPerUser;

    /**
     * Taille maximale acceptee pour la sortie stdout/stderr de Nmap
     * (correctif securite FAIBLE, audit 04/08/2026) : {@code readAllBytes()}
     * n'avait jusqu'ici aucune limite — un subnet est deja borne a /24 (254
     * hotes) donc la sortie XML normale reste tres en-deca de cette limite,
     * mais une version de nmap inhabituellement verbeuse ou un comportement
     * inattendu ne doit pas pouvoir faire croitre la consommation memoire du
     * process applicatif sans borne.
     */
    @Value("${subnetory.scan.max-output-bytes:10485760}")
    private long maxOutputBytes;

    private Semaphore globalScanSemaphore;

    private final Object perUserScanLock = new Object();
    private final Map<String, Integer> activeScansByUser = new HashMap<>();

    public ScanService(SubnetService subnetService, AddressService addressService,
                       RestoreMaintenanceGate restoreMaintenanceGate) {
        this.subnetService = subnetService;
        this.addressService = addressService;
        this.restoreMaintenanceGate = restoreMaintenanceGate;
    }

    @PostConstruct
    void initScanSemaphore() {
        this.globalScanSemaphore = new Semaphore(maxConcurrentScans);
    }

    /**
     * Lance un scan Nmap sur le subnet identifié.
     *
     * @param subnetId    identifiant du subnet en base
     * @param request     paramètres du scan (method, override)
     * @param currentUser utilisateur déclenchant le scan (pour audit)
     * @return résultat du scan avec statistiques
     * @throws ScanException si nmap est absent, si le subnet est trop grand,
     *                       si le scan expire ou si une erreur d'exécution survient
     */
    public ScanResponse scan(Long subnetId, ScanRequest request, String currentUser)
            throws ScanException {

        // Correctif securite FAIBLE (second audit externe 04/08/2026) :
        // verification precoce, en plus de celle juste avant l'ecriture
        // (voir plus bas) — evite de lancer un scan Nmap (potentiellement
        // long) alors qu'une restauration est deja en cours au moment de
        // l'appel, en plus de couvrir le cas ou elle demarre pendant le scan.
        if (restoreMaintenanceGate.isActive()) {
            throw new ScanException(
                    "Une restauration de sauvegarde est en cours : les scans sont temporairement "
                            + "indisponibles. Reessayez une fois la restauration terminee.",
                    ScanException.Reason.RESTORE_IN_PROGRESS);
        }

        Subnet subnet = subnetService.getEntityById(subnetId);

        validateSubnetSize(subnet.getNetwork());

        String cidr = subnet.getNetwork();
        log.info("Scan started: subnet={} cidr={} user={} override={}",
                subnetId, cidr, currentUser, request.override());

        String userKey = currentUser == null || currentUser.isBlank() ? "anonymous" : currentUser;
        NmapExecution execution = executeNmapThrottled(cidr, request, userKey);
        List<NmapXmlParser.NmapHost> hosts = filterAssignableHosts(cidr, execution.hosts());

        // Correctif securite FAIBLE (second audit externe 04/08/2026) : une
        // restauration a pu demarrer PENDANT l'execution de ce scan (deja
        // accepte avant que RestoreMaintenanceFilter ne bloque les nouvelles
        // requetes de mutation) — voir ScanException.Reason.RESTORE_IN_PROGRESS.
        // Verifie ici, juste avant l'ecriture, plutot qu'au debut de scan()
        // ou le scan pourrait encore durer jusqu'a timeoutSeconds.
        if (restoreMaintenanceGate.isActive()) {
            throw new ScanException(
                    "Une restauration de sauvegarde a demarre pendant ce scan : les resultats "
                            + "ne sont pas enregistres. Relancez le scan une fois la restauration terminee.",
                    ScanException.Reason.RESTORE_IN_PROGRESS);
        }

        // Construire les entrées pour le bulk-upsert
        List<BulkUpsertRequest.BulkUpsertEntry> entries = hosts.stream()
                .map(h -> new BulkUpsertRequest.BulkUpsertEntry(
                        h.ip(),
                        subnetId,
                        h.mac(),
                        h.hostname(),
                        "Découvert par scan Nmap",
                        false,
                        "nmap"
                ))
                .toList();

        BulkUpsertRequest bulkRequest = new BulkUpsertRequest(entries, request.override());
        BulkUpsertResponse bulkResult = addressService.bulkUpsert(bulkRequest, currentUser);

        int updatedLastSeen = bulkResult.skipped();
        int overwritten = request.override() ? bulkResult.updated() : 0;

        log.info("Scan completed: cidr={} found={} created={} updatedLastSeen={} overwritten={} errors={}",
                cidr, hosts.size(), bulkResult.created(), updatedLastSeen, overwritten,
                bulkResult.errors().size());

        List<String> errorDetails = bulkResult.errors().stream()
                .map(e -> e.address() + ": " + e.reason())
                .toList();
        List<ScanResponse.ScanHost> detectedHosts = hosts.stream()
                .map(h -> new ScanResponse.ScanHost(h.ip(), h.mac(), h.hostname()))
                .toList();

        return new ScanResponse(
                subnetId,
                cidr,
                "nmap",
                hosts.size(),
                detectedHosts,
                bulkResult.created(),
                updatedLastSeen,
                overwritten,
                bulkResult.errors().size(),
                errorDetails,
                execution.commandPreview(),
                execution.exitCode(),
                execution.logLines(),
                OffsetDateTime.now()
        );
    }

    public String commandPreview(String cidr, ScanRequest request) {
        try {
            return String.join(" ", buildCommand(cidr, request));
        } catch (ScanException e) {
            return String.join(" ", buildCommandWithoutCustomDns(cidr, request));
        }
    }

    // -------------------------------------------------------
    // Validation
    // -------------------------------------------------------

    /**
     * Valide que le subnet est ≤ /24 en comparant la longueur de préfixe.
     *
     * <p>On compare le préfixe CIDR directement plutôt que le nombre d'hôtes
     * retourné par IpUtils (qui peut varier selon inclusiveHostCount).
     * /24 → accepté, /23 → refusé, /16 → refusé.</p>
     */
    private void validateSubnetSize(String cidr) throws ScanException {
        int prefix = prefixLengthOf(cidr);
        if (prefix < MIN_ALLOWED_PREFIX_LENGTH) {
            long usableHosts = Math.max(0, IpUtils.usableAddressCount(cidr) - 2);
            throw new ScanException(
                    String.format(
                            "Subnet %s is too large for synchronous scan (%d usable hosts, max 254). " +
                            "Only subnets up to /24 are supported.",
                            cidr, usableHosts),
                    ScanException.Reason.SUBNET_TOO_LARGE
            );
        }
    }

    /** Extrait la longueur de préfixe depuis un CIDR, ex: "10.0.0.0/24" → 24. */
    private static int prefixLengthOf(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0 || slash == cidr.length() - 1) {
            throw new IllegalArgumentException("Invalid CIDR notation: " + cidr);
        }
        return Integer.parseInt(cidr.substring(slash + 1));
    }

    /**
     * Vérifie que nmap est accessible.
     * Exécute {@code nmap --version} et vérifie le code de retour.
     *
     * <p>Correctif sécurité FAIBLE (second audit externe 04/08/2026) : cette
     * sonde lance elle-même un processus OS distinct. Appelée désormais
     * seulement après acquisition des deux quotas de concurrence (par
     * utilisateur puis global, voir {@link #executeNmapThrottled}), plutôt
     * qu'inconditionnellement au tout début de {@link #scan}. Auparavant, un
     * appelant déjà au-delà de sa limite de concurrence continuait quand
     * même de déclencher un processus {@code nmap --version} avant d'être
     * rejeté — cette sonde n'était donc jamais réellement throttlée,
     * contournant partiellement l'objectif même du garde-fou de
     * concurrence.</p>
     */
    private void assertNmapAvailable() throws ScanException {
        try {
            Process probe = new ProcessBuilder(nmapPath, "--version")
                    .redirectErrorStream(true)
                    .start();
            probe.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            boolean finished = probe.waitFor(5, TimeUnit.SECONDS);
            if (!finished || probe.exitValue() != 0) {
                throw new ScanException(
                        "nmap is not responding correctly. Check installation.",
                        ScanException.Reason.TOOL_NOT_AVAILABLE);
            }
        } catch (ScanException e) {
            throw e;
        } catch (Exception e) {
            throw new ScanException(
                    "nmap is not installed or not found in PATH. " +
                    "Install nmap and ensure it is accessible from the server.",
                    ScanException.Reason.TOOL_NOT_AVAILABLE);
        }
    }

    // -------------------------------------------------------
    // Limitation de concurrence — global + par utilisateur (correctif
    // sécurité FAIBLE/MOYEN, audit 04/08/2026)
    // -------------------------------------------------------

    private NmapExecution executeNmapThrottled(String cidr, ScanRequest request, String userKey)
            throws ScanException {
        acquirePerUserSlot(userKey);
        try {
            if (!globalScanSemaphore.tryAcquire()) {
                throw new ScanException(
                        "Trop de scans Nmap sont déjà en cours sur cette instance (limite : "
                                + maxConcurrentScans + "). Réessayez dans quelques instants.",
                        ScanException.Reason.TOO_MANY_CONCURRENT_SCANS);
            }
            try {
                // Correctif securite FAIBLE (second audit externe 04/08/2026) :
                // sonde deplacee ici, APRES acquisition des deux quotas —
                // voir la javadoc de assertNmapAvailable().
                assertNmapAvailable();
                return executeNmap(cidr, request);
            } finally {
                globalScanSemaphore.release();
            }
        } finally {
            releasePerUserSlot(userKey);
        }
    }

    private void acquirePerUserSlot(String userKey) throws ScanException {
        synchronized (perUserScanLock) {
            int current = activeScansByUser.getOrDefault(userKey, 0);
            if (current >= maxConcurrentScansPerUser) {
                throw new ScanException(
                        "Vous avez déjà " + current + " scan(s) Nmap en cours (limite : "
                                + maxConcurrentScansPerUser
                                + "). Attendez qu'un scan se termine avant d'en lancer un nouveau.",
                        ScanException.Reason.TOO_MANY_CONCURRENT_SCANS);
            }
            activeScansByUser.put(userKey, current + 1);
        }
    }

    private void releasePerUserSlot(String userKey) {
        synchronized (perUserScanLock) {
            int current = activeScansByUser.getOrDefault(userKey, 0);
            if (current <= 1) {
                activeScansByUser.remove(userKey);
            } else {
                activeScansByUser.put(userKey, current - 1);
            }
        }
    }

    // -------------------------------------------------------
    // Exécution Nmap — Fix 1 : stdout/stderr en parallèle, timeout strict
    // -------------------------------------------------------

    private NmapExecution executeNmap(String cidr, ScanRequest request) throws ScanException {
        // Arguments contrôlés — aucun paramètre libre n'est transmis au shell.
        // Le CIDR provient exclusivement de la base de données.
        List<String> command = buildCommand(cidr, request);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false); // stderr séparé pour ne pas polluer le XML

        String commandPreview = String.join(" ", command);
        log.debug("Executing nmap: {}", commandPreview);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new ScanException(
                    "Failed to start nmap process: " + e.getMessage(),
                    ScanException.Reason.EXECUTION_FAILED);
        }

        // Lire stdout et stderr en parallèle pour éviter le blocage des buffers OS.
        // Un process dont le buffer stderr est plein sans lecteur peut bloquer indéfiniment.
        CompletableFuture<byte[]> stdoutFuture = readAllBytesAsync(process.getInputStream());
        CompletableFuture<byte[]> stderrFuture = readAllBytesAsync(process.getErrorStream());

        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new ScanException(
                        "Scan timed out after " + timeoutSeconds + "s for " + cidr + ".",
                        ScanException.Reason.TIMEOUT);
            }

            byte[] stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            byte[] stderr = stderrFuture.get(5, TimeUnit.SECONDS);

            if (process.exitValue() != 0 && stdout.length == 0) {
                String error = new String(stderr).trim();
                throw new ScanException(
                        "Nmap exited with code " + process.exitValue() +
                        (error.isEmpty() ? "" : ": " + error),
                        ScanException.Reason.EXECUTION_FAILED);
            }

            try (InputStream xml = new ByteArrayInputStream(stdout)) {
                List<NmapXmlParser.NmapHost> hosts = NmapXmlParser.parse(xml);
                List<String> logLines = new ArrayList<>();
                logLines.add("Démarrage du scan sur " + cidr + ".");
                logLines.add("Commande générée par Subnetory avec options contrôlées.");
                if (stderr.length > 0) {
                    logLines.add("Sortie diagnostic Nmap : " + abbreviate(new String(stderr).trim(), 500));
                }
                logLines.add("Code retour Nmap : " + process.exitValue() + ".");
                logLines.add("Hôtes détectés : " + hosts.size() + ".");
                return new NmapExecution(hosts, commandPreview, process.exitValue(), logLines);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new ScanException("Scan interrupted", ScanException.Reason.EXECUTION_FAILED);
        } catch (ExecutionException | TimeoutException e) {
            process.destroyForcibly();
            throw new ScanException(
                    "Failed to read nmap output: " + e.getMessage(),
                    ScanException.Reason.EXECUTION_FAILED);
        } catch (ScanException e) {
            throw e;
        } catch (Exception e) {
            throw new ScanException(
                    "Failed to parse nmap XML output: " + e.getMessage(),
                    ScanException.Reason.PARSE_ERROR);
        }
    }

    /**
     * Lit toutes les données d'un InputStream dans un thread séparé.
     * Utilisé pour lire stdout et stderr en parallèle sans blocage.
     *
     * <p>Bornée à {@link #maxOutputBytes} (correctif sécurité FAIBLE, audit
     * 04/08/2026) : {@code readAllBytes()} seul n'imposait aucune limite.</p>
     */
    private CompletableFuture<byte[]> readAllBytesAsync(InputStream stream) {
        long limit = maxOutputBytes;
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return readBounded(stream, limit);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException(
                        "Nmap output exceeded the configured limit of " + maxBytes + " bytes.");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private List<String> buildCommand(String cidr, ScanRequest request) throws ScanException {
        List<String> command = new ArrayList<>();
        command.add(nmapPath);
        command.add("-sn");
        if (Boolean.TRUE.equals(request.resolveDns())) {
            command.add("-R");
            List<String> dnsServers = normalizeDnsServers(request.dnsServers());
            if (!dnsServers.isEmpty()) {
                command.add("--dns-servers");
                command.add(String.join(",", dnsServers));
            }
        } else {
            command.add("-n");
        }
        if (Boolean.FALSE.equals(request.arpPing())) {
            command.add("--disable-arp-ping");
        }
        switch (request.timing()) {
            case "fast" -> command.add("-T4");
            case "gentle" -> command.add("-T2");
            default -> { }
        }
        command.add("-oX");
        command.add("-");
        command.add(cidr);
        return command;
    }

    private List<String> buildCommandWithoutCustomDns(String cidr, ScanRequest request) {
        List<String> command = new ArrayList<>();
        command.add(nmapPath);
        command.add("-sn");
        command.add(Boolean.TRUE.equals(request.resolveDns()) ? "-R" : "-n");
        if (Boolean.FALSE.equals(request.arpPing())) {
            command.add("--disable-arp-ping");
        }
        switch (request.timing()) {
            case "fast" -> command.add("-T4");
            case "gentle" -> command.add("-T2");
            default -> { }
        }
        command.add("-oX");
        command.add("-");
        command.add(cidr);
        return command;
    }

    private List<String> normalizeDnsServers(String value) throws ScanException {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> servers = new LinkedHashSet<>();
        for (String candidate : value.trim().split("[,;\\s]+")) {
            if (candidate.isBlank()) continue;
            if (!IpUtils.isValidIpv4(candidate)) {
                throw new ScanException(
                        "Serveur DNS invalide : " + candidate,
                        ScanException.Reason.INVALID_OPTIONS);
            }
            servers.add(candidate);
            if (servers.size() > 5) {
                throw new ScanException(
                        "La liste DNS est limitée à 5 serveurs.",
                        ScanException.Reason.INVALID_OPTIONS);
            }
        }
        return List.copyOf(servers);
    }

    private List<NmapXmlParser.NmapHost> filterAssignableHosts(String cidr, List<NmapXmlParser.NmapHost> hosts) {
        String networkAddress = IpUtils.networkAddress(cidr);
        String broadcastAddress = IpUtils.broadcastAddress(cidr);
        return hosts.stream()
                .filter(host -> !host.ip().equals(networkAddress))
                .filter(host -> !host.ip().equals(broadcastAddress))
                .toList();
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + "...";
    }

    private record NmapExecution(
            List<NmapXmlParser.NmapHost> hosts,
            String commandPreview,
            int exitCode,
            List<String> logLines
    ) {}
}
