package dev.subnetory.service;

import dev.subnetory.csv.AddressCsvParser;
import dev.subnetory.csv.AddressXlsxParser;
import dev.subnetory.csv.AddressCsvParser.CsvRow;
import dev.subnetory.csv.CsvParseException;
import dev.subnetory.domain.Address;
import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.*;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.repository.AddressSpecifications;
import dev.subnetory.util.IpUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class AddressService {

    private static final String DEFAULT_SOURCE = "manual";
    private static final List<String> VALID_SOURCES =
            List.of("manual", "api", "csv", "xlsx", "nmap", "arp-scan", "dns");

    private final AddressRepository addressRepository;
    private final SubnetService subnetService;
    private final AddressCsvParser csvParser;
    private final AddressXlsxParser xlsxParser;
    private final ContextAccessService contextAccessService;

    public AddressService(AddressRepository addressRepository,
                          SubnetService subnetService,
                          AddressCsvParser csvParser,
                          AddressXlsxParser xlsxParser,
                          ContextAccessService contextAccessService) {
        this.addressRepository = addressRepository;
        this.subnetService = subnetService;
        this.csvParser = csvParser;
        this.xlsxParser = xlsxParser;
        this.contextAccessService = contextAccessService;
    }

    // -------------------------------------------------------
    // Lecture
    // -------------------------------------------------------

    /**
     * Recherche multi-critères. Tous les paramètres sont optionnels et combinés en AND.
     */
    public Page<AddressResponse> search(
            String hostname, String hostnameContains, String mac,
            String q, Long siteId, Long contextId, Long subnetId,
            Pageable pageable) {
        if (contextId != null) contextAccessService.requireAccess(contextId);
        if (subnetId != null) subnetService.getEntityById(subnetId);
        Specification<Address> spec = AddressSpecifications.withFilters(
                hostname, hostnameContains, mac, q, siteId, contextId, subnetId,
                contextAccessService.allowedContextIds());
        return addressRepository.findAll(spec, pageable).map(this::toResponse);
    }

    /**
     * Retourne toutes les adresses correspondant aux filtres, sans pagination.
     * Utilisé par l'export CSV {@code GET /api/v1/addresses/export/csv}.
     *
     * <p>Les paramètres de filtre sont identiques à {@link #search} — tous optionnels
     * et cumulables en AND. Sans filtre, retourne toutes les adresses.</p>
     */
    public List<AddressResponse> searchAll(
            String hostname, String hostnameContains, String mac,
            String q, Long siteId, Long contextId, Long subnetId) {
        if (contextId != null) contextAccessService.requireAccess(contextId);
        if (subnetId != null) subnetService.getEntityById(subnetId);
        Specification<Address> spec = AddressSpecifications.withFilters(
                hostname, hostnameContains, mac, q, siteId, contextId, subnetId,
                contextAccessService.allowedContextIds());
        return addressRepository.findAll(spec).stream()
                .map(this::toResponse)
                .toList();
    }

    public Page<AddressResponse> findBySubnet(Long subnetId, Pageable pageable) {
        subnetService.getEntityById(subnetId);
        return addressRepository.findBySubnetId(subnetId, pageable).map(this::toResponse);
    }

    public AddressResponse findById(Long id) {
        return toResponse(getAccessibleAddress(id));
    }

    public AddressResponse findByIp(String ip) {
        var allowedContextIds = contextAccessService.allowedContextIds();
        if (allowedContextIds.isEmpty()) {
            throw new ResourceNotFoundException("Address", ip);
        }
        var matches = addressRepository.findAllByIpExactAndContextIdIn(
                ip, allowedContextIds);
        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("Address", ip);
        }
        if (matches.size() > 1) {
            throw new ConflictException(
                    "Address " + ip + " exists in multiple subnets. Use the address search with subnetId.");
        }
        return toResponse(matches.get(0));
    }

    public List<AddressResponse> findByHostname(String hostname) {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return List.of();
        return addressRepository.findByHostnameAndContextIdIn(hostname, allowedIds)
                .stream().map(this::toResponse).toList();
    }

    // -------------------------------------------------------
    // Écriture — CRUD standard
    // -------------------------------------------------------

    @Transactional
    public AddressResponse create(AddressRequest request, String currentUser) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        if (addressRepository.findByIpExactAndSubnetId(request.address(), request.subnetId()).isPresent()) {
            throw new ConflictException("Address " + request.address() + " is already assigned");
        }
        assertInSubnet(request.address(), subnet);

        Address address = new Address();
        address.setAddress(request.address());
        address.setMac(normalizedMac(request.mac()));
        address.setHostname(request.hostname());
        address.setDescription(request.description());
        address.setContext(subnet.getContext());
        address.setSite(subnet.getSite());
        address.setSubnet(subnet);
        address.setModifiedBy(currentUser);
        address.setTemporary(request.temporary());
        address.setDiscoverySource(
                validSource(request.discoverySource(), DEFAULT_SOURCE));
        // Filet de securite (02/08/2026, correctif MOYENNE) : le pre-controle
        // findByIpExactAndSubnetId() ci-dessus laisse une fenetre de course
        // entre deux requetes concurrentes visant la meme adresse dans le
        // meme sous-reseau (ex. deux operateurs qui reservent la meme IP au
        // meme instant, ou une reservation groupee en parallele d'un import).
        // Les deux pre-controles peuvent passer avant que l'un des deux
        // INSERT ne s'execute ; le second declenche alors la contrainte
        // d'unicite reelle en base (uq_addresses_address_subnet, migration
        // V11) sous forme de DataIntegrityViolationException brute, non geree
        // jusqu'ici cote controleur (500 generique au lieu du message
        // "adresse deja assignee"). GenerationType.IDENTITY sur Address force
        // l'INSERT immediat lors de save(), donc l'exception remonte bien
        // dans ce bloc try.
        try {
            return toResponse(addressRepository.save(address));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Address " + request.address() + " is already assigned");
        }
    }

    @Transactional
    public AddressResponse update(Long id, AddressRequest request, String currentUser) {
        Address address = getAccessibleAddress(id);
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        if (!extractIp(address.getAddress()).equals(request.address())
                && addressRepository.findByIpExactAndSubnetId(request.address(), request.subnetId()).isPresent()) {
            throw new ConflictException("Address " + request.address() + " is already assigned");
        }
        assertInSubnet(request.address(), subnet);

        address.setAddress(request.address());
        address.setMac(normalizedMac(request.mac()));
        address.setHostname(request.hostname());
        address.setDescription(request.description());
        address.setContext(subnet.getContext());
        address.setSite(subnet.getSite());
        address.setSubnet(subnet);
        address.setModifiedBy(currentUser);
        address.setTemporary(request.temporary());
        // discovery_source non modifié sur PUT
        // Filet de securite (02/08/2026, correctif MOYENNE) : meme fenetre de
        // course qu'en creation (voir create() ci-dessus). saveAndFlush()
        // (et non save()) est indispensable ici : "address" est une entite
        // deja managee (chargee par getAccessibleAddress()), donc l'UPDATE
        // serait normalement differe au flush de fin de transaction — hors
        // de la portee de ce try/catch — sans un flush explicite.
        try {
            return toResponse(addressRepository.saveAndFlush(address));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Address " + request.address() + " is already assigned");
        }
    }

    // -------------------------------------------------------
    // PATCH — mise à jour partielle
    // Utilise Map<String,Object> pour distinguer champ absent vs null
    // -------------------------------------------------------

    @Transactional
    public AddressResponse patch(Long id, Map<String, Object> fields, String currentUser) {
        Address address = getAccessibleAddress(id);

        if (fields.containsKey("mac")) {
            address.setMac(normalizedMac(
                    fields.get("mac") != null ? fields.get("mac").toString() : null));
        }
        if (fields.containsKey("hostname")) {
            address.setHostname(
                    fields.get("hostname") != null ? fields.get("hostname").toString() : null);
        }
        if (fields.containsKey("description")) {
            address.setDescription(
                    fields.get("description") != null ? fields.get("description").toString() : null);
        }
        if (fields.containsKey("temporary")) {
            Object v = fields.get("temporary");
            if (v instanceof Boolean b) address.setTemporary(b);
            else if (v != null) address.setTemporary(Boolean.parseBoolean(v.toString()));
        }

        address.setModifiedBy(currentUser);
        return toResponse(addressRepository.save(address));
    }

    @Transactional
    public void delete(Long id) {
        Address address = getAccessibleAddress(id);
        addressRepository.delete(address);
    }

    // -------------------------------------------------------
    // Upsert par IP — PUT /by-ip/{ip}
    // -------------------------------------------------------

    @Transactional
    public AddressResponse upsertByIp(String ip, AddressUpsertRequest request,
                                      boolean override, String currentUser) {
        Optional<Address> existing = addressRepository.findByIpExactAndSubnetId(ip, request.subnetId());
        OffsetDateTime now = OffsetDateTime.now();

        if (existing.isEmpty()) {
            // Création
            Subnet subnet = subnetService.getEntityById(request.subnetId());
            assertInSubnet(ip, subnet);
            Address address = new Address();
            address.setAddress(ip);
            address.setMac(normalizedMac(request.mac()));
            address.setHostname(request.hostname());
            address.setDescription(request.description());
            address.setContext(subnet.getContext());
            address.setSite(subnet.getSite());
            address.setSubnet(subnet);
            address.setModifiedBy(currentUser);
            address.setTemporary(request.temporary());
            address.setDiscoverySource(validSource(request.discoverySource(), DEFAULT_SOURCE));
            address.setLastSeenAt(now);
            return toResponse(addressRepository.save(address));
        }

        // IP existante
        Address address = existing.get();
        contextAccessService.requireResourceAccess(address.getContext().getId(), "Address", ip);
        address.setLastSeenAt(now); // toujours mis à jour

        if (override) {
            if (request.mac() != null)         address.setMac(normalizedMac(request.mac()));
            if (request.hostname() != null)    address.setHostname(request.hostname());
            if (request.description() != null) address.setDescription(request.description());
            address.setTemporary(request.temporary());
            address.setModifiedBy(currentUser);
            // discovery_source jamais modifié sur IP existante
            return toResponse(addressRepository.save(address));
        }
        // Pas d'override : simple "constat" (scan/import), aucune modification
        // métier. Requête ciblée plutôt que save() pour ne pas incrémenter
        // Address.version à chaque passage — voir AddressRepository.touchLastSeen.
        addressRepository.touchLastSeen(address.getId(), now);
        return toResponse(address);
    }

    // -------------------------------------------------------
    // Bulk-upsert — POST /bulk-upsert
    // -------------------------------------------------------

    @Transactional
    public BulkUpsertResponse bulkUpsert(BulkUpsertRequest request, String currentUser) {
        int created = 0, updated = 0, skipped = 0;
        List<BulkUpsertResponse.BulkUpsertError> errors = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        for (BulkUpsertRequest.BulkUpsertEntry entry : request.addresses()) {
            try {
                Optional<Address> existing = addressRepository.findByIpExactAndSubnetId(
                        entry.address(), entry.subnetId());

                if (existing.isEmpty()) {
                    // Création.
                    // On utilise findEntityByIdOptional() au lieu de getEntityById() : ce
                    // dernier est expose par un bean @Transactional(readOnly=true)
                    // (SubnetService) et, s'il leve ResourceNotFoundException, Spring
                    // marque la transaction PHYSIQUE partagee "rollback-only" des la
                    // sortie de cet appel imbrique — avant meme que le catch(Exception)
                    // de cette boucle n'ait eu l'occasion d'agir. Le rapport continuerait
                    // alors a compter cette ligne (et toutes les suivantes) comme
                    // creees/mises a jour, mais un UnexpectedRollbackException annulerait
                    // silencieusement tout le lot au commit final — voir resolveSubnetId()
                    // ci-dessous, qui documente et applique deja ce meme contournement
                    // pour l'import CSV (audit i18n/fonctionnel du 02/08/2026).
                    Subnet subnet = subnetService.findEntityByIdOptional(entry.subnetId())
                            .orElseThrow(() -> new ResourceNotFoundException("Subnet", entry.subnetId()));
                    assertInSubnet(entry.address(), subnet);
                    Address address = new Address();
                    address.setAddress(entry.address());
                    address.setMac(normalizedMac(entry.mac()));
                    address.setHostname(entry.hostname());
                    address.setDescription(entry.description());
                    address.setContext(subnet.getContext());
                    address.setSite(subnet.getSite());
                    address.setSubnet(subnet);
                    address.setModifiedBy(currentUser);
                    address.setTemporary(entry.temporary());
                    address.setDiscoverySource(validSource(entry.discoverySource(), DEFAULT_SOURCE));
                    address.setLastSeenAt(now);
                    addressRepository.save(address);
                    created++;
                } else {
                    Address address = existing.get();
                    // Meme raison que ci-dessus : contextAccessService.requireResourceAccess()
                    // est expose par un bean @Transactional(readOnly=true) et leve
                    // ResourceNotFoundException, ce qui marquerait la transaction partagee
                    // rollback-only. On utilise ici canAccess() (non-levant) et on leve
                    // l'exception nous-memes, directement dans le corps de bulkUpsert() —
                    // elle ne traverse alors aucun proxy transactionnel imbrique avant
                    // d'etre interceptee par le catch(Exception) de cette boucle.
                    if (!contextAccessService.canAccess(address.getContext().getId())) {
                        throw new ResourceNotFoundException("Address", entry.address());
                    }
                    address.setLastSeenAt(now); // toujours

                    if (request.override()) {
                        if (entry.mac() != null)         address.setMac(normalizedMac(entry.mac()));
                        if (entry.hostname() != null)    address.setHostname(entry.hostname());
                        if (entry.description() != null) address.setDescription(entry.description());
                        address.setTemporary(entry.temporary());
                        address.setModifiedBy(currentUser);
                        addressRepository.save(address);
                        updated++;
                    } else {
                        // Pas d'override : juste last_seen_at. Requête ciblée
                        // (pas de save() sur l'entité complète) pour ne pas
                        // incrémenter Address.version à chaque scan/import —
                        // voir AddressRepository.touchLastSeen.
                        addressRepository.touchLastSeen(address.getId(), now);
                        skipped++;
                    }
                    // discovery_source jamais modifié sur entrée existante
                }
            } catch (Exception e) {
                errors.add(new BulkUpsertResponse.BulkUpsertError(
                        entry.address(), e.getMessage()));
            }
        }
        return new BulkUpsertResponse(created, updated, skipped, errors);
    }

    // -------------------------------------------------------
    // Helpers privés
    // -------------------------------------------------------

    private void assertInSubnet(String ip, Subnet subnet) {
        if (!IpUtils.isInNetwork(ip, subnet.getNetwork())) {
            throw new ConflictException(
                    "Address " + ip + " is not in subnet " + subnet.getNetwork());
        }
    }

    private Address getAccessibleAddress(Long id) {
        Address address = addressRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Address", id));
        contextAccessService.requireResourceAccess(address.getContext().getId(), "Address", id);
        return address;
    }

    private String normalizedMac(String mac) {
        if (mac == null || mac.isBlank()) return null;
        return mac.toLowerCase().trim();
    }

    private String extractIp(String inetAddress) {
        if (inetAddress == null) return "";
        int slash = inetAddress.indexOf('/');
        return slash >= 0 ? inetAddress.substring(0, slash) : inetAddress;
    }

    private String validSource(String source, String defaultValue) {
        if (source == null || source.isBlank()) return defaultValue;
        if (VALID_SOURCES.contains(source)) return source;
        return defaultValue;
    }

    // -------------------------------------------------------
    // Mapping
    // -------------------------------------------------------

    public AddressResponse toResponse(Address a) {
        return new AddressResponse(
                a.getId(),
                extractIp(a.getAddress()),
                a.getMac(),
                a.getHostname(),
                a.getDescription(),
                a.getContext().getId(),
                a.getContext().getName(),
                a.getSite().getId(),
                a.getSite().getName(),
                a.getSubnet().getId(),
                a.getSubnet().getNetwork(),
                a.getModifiedBy(),
                a.isTemporary(),
                a.getLastSeenAt(),
                a.getDiscoverySource(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }

    // -------------------------------------------------------
    // Import CSV — Sprint 2.0
    // -------------------------------------------------------

    /**
     * Importe des adresses IP depuis un fichier CSV.
     *
     * <p>Flux :</p>
     * <ol>
     *   <li>Parsing CSV via {@link AddressCsvParser}</li>
     *   <li>Résolution subnet : subnet_id prioritaire sur subnet_network</li>
     *   <li>Si subnet_id et subnet_network tous deux fournis : vérification de cohérence</li>
     *   <li>Si subnet_network ambigu (plusieurs subnets) : erreur ligne</li>
     *   <li>Construction BulkUpsertRequest</li>
     *   <li>Appel bulkUpsert() — source de vérité des règles métier</li>
     * </ol>
     *
     * @param inputStream flux CSV
     * @param override    si true, écrase les champs existants
     * @param currentUser utilisateur déclenchant l'import
     * @return rapport d'import
     * @throws CsvParseException si le fichier CSV est structurellement invalide
     */
    @Transactional
    public CsvImportResponse importCsv(java.io.InputStream inputStream,
                                       boolean override,
                                       String currentUser) throws CsvParseException {
        AddressCsvParser.ParseResult parsed = csvParser.parse(inputStream);
        return importParsedRows(parsed, override, currentUser, null);
    }

    @Transactional
    public CsvImportResponse importCsv(java.io.InputStream inputStream,
                                       boolean override,
                                       String currentUser,
                                       Long requiredContextId) throws CsvParseException {
        AddressCsvParser.ParseResult parsed = csvParser.parse(inputStream);
        return importParsedRows(parsed, override, currentUser, requiredContextId);
    }

    /**
     * Importe des adresses IP depuis un fichier XLSX.
     *
     * <p>Réutilise la même logique métier que l'import CSV : seul le parser change.</p>
     */
    @Transactional
    public CsvImportResponse importXlsx(java.io.InputStream inputStream,
                                        boolean override,
                                        String currentUser) throws CsvParseException {
        AddressCsvParser.ParseResult parsed = xlsxParser.parse(inputStream);
        return importParsedRows(parsed, override, currentUser, null);
    }

    @Transactional
    public CsvImportResponse importXlsx(java.io.InputStream inputStream,
                                        boolean override,
                                        String currentUser,
                                        Long requiredContextId) throws CsvParseException {
        AddressCsvParser.ParseResult parsed = xlsxParser.parse(inputStream);
        return importParsedRows(parsed, override, currentUser, requiredContextId);
    }

    private CsvImportResponse importParsedRows(AddressCsvParser.ParseResult parsed,
                                               boolean override,
                                               String currentUser,
                                               Long requiredContextId) {
        if (requiredContextId != null) {
            contextAccessService.requireAccess(requiredContextId);
        }

        List<CsvImportResponse.CsvRowError> allErrors = new ArrayList<>(parsed.errors());
        List<BulkUpsertRequest.BulkUpsertEntry> entries = new ArrayList<>();

        // Mapping adresse → numéro de ligne CSV pour les erreurs bulk-upsert.
        java.util.Map<String, Integer> rowByAddress = new java.util.HashMap<>();

        for (CsvRow row : parsed.rows()) {
            try {
                Long resolvedSubnetId = resolveSubnetId(row, requiredContextId);
                entries.add(new BulkUpsertRequest.BulkUpsertEntry(
                        row.address(),
                        resolvedSubnetId,
                        row.mac(),
                        row.hostname(),
                        row.description(),
                        row.temporary(),
                        row.discoverySource()
                ));

                // Conserver le numéro de ligne pour les erreurs bulk éventuelles.
                rowByAddress.putIfAbsent(row.address(), row.row());
            } catch (Exception e) {
                allErrors.add(new CsvImportResponse.CsvRowError(
                        row.row(),
                        row.address(),
                        e.getMessage()
                ));
            }
        }

        BulkUpsertResponse bulk = bulkUpsert(
                new BulkUpsertRequest(entries, override), currentUser);

        // Fusionner les erreurs bulk avec le numéro de ligne retrouvé via rowByAddress.
        bulk.errors().forEach(e ->
                allErrors.add(new CsvImportResponse.CsvRowError(
                        rowByAddress.getOrDefault(e.address(), 0),
                        e.address(),
                        e.reason()
                )));

        int totalRows = parsed.rows().size() + parsed.errors().size();

        // updatedLastSeen = updated + skipped car last_seen_at est mis à jour dans les deux cas :
        // - updated = IP existante avec override
        // - skipped = IP existante sans override
        return new CsvImportResponse(
                totalRows,
                bulk.created(),
                bulk.updated() + bulk.skipped(),
                0,
                allErrors.size(),
                allErrors
        );
    }

    /**
     * Résout le subnet_id depuis une ligne CSV.
     * subnet_id prioritaire. Si absent, résolution via subnet_network.
     * Si les deux sont fournis, vérification de cohérence.
     *
     * Important :
     * On utilise findEntityByIdOptional() au lieu de getEntityById() pour les erreurs CSV attendues.
     * Cela évite qu'une ResourceNotFoundException lancée par le proxy transactionnel de SubnetService
     * marque la transaction courante en rollback-only alors que l'erreur doit simplement être reportée
     * dans le rapport d'import CSV.
     */
    private Long resolveSubnetId(CsvRow row, Long requiredContextId) {
        if (row.subnetId() != null && row.subnetNetwork() != null) {
            // Les deux fournis — vérifier cohérence sans déclencher rollback-only.
            Subnet subnet = subnetService.findEntityByIdOptional(row.subnetId())
                    .orElseThrow(() -> new ResourceNotFoundException("Subnet", row.subnetId()));
            requireSubnetInContext(subnet, requiredContextId);

            if (!subnet.getNetwork().equals(row.subnetNetwork())) {
                throw new ConflictException(
                        "subnet_id " + row.subnetId() + " network is '" + subnet.getNetwork() +
                                "' but subnet_network is '" + row.subnetNetwork() + "' — they must match");
            }
            return row.subnetId();
        }

        if (row.subnetId() != null) {
            // Vérifier que le subnet existe sans déclencher rollback-only.
            Subnet subnet = subnetService.findEntityByIdOptional(row.subnetId())
                    .orElseThrow(() -> new ResourceNotFoundException("Subnet", row.subnetId()));
            requireSubnetInContext(subnet, requiredContextId);
            return row.subnetId();
        }

        // Résolution par subnet_network.
        List<Subnet> matches = subnetService.findAllByNetwork(row.subnetNetwork());
        if (requiredContextId != null) {
            matches = matches.stream()
                    .filter(subnet -> requiredContextId.equals(subnet.getContext().getId()))
                    .toList();
        }

        if (matches.isEmpty()) {
            throw new ResourceNotFoundException("Subnet", row.subnetNetwork());
        }

        if (matches.size() > 1) {
            String ids = matches.stream()
                    .map(s -> String.valueOf(s.getId()))
                    .collect(java.util.stream.Collectors.joining(", "));

            throw new ConflictException(
                    "subnet_network '" + row.subnetNetwork() + "' matches " + matches.size() +
                            " subnets (ids: " + ids + "). Use subnet_id to disambiguate.");
        }

        return matches.get(0).getId();
    }

    private void requireSubnetInContext(Subnet subnet, Long requiredContextId) {
        if (requiredContextId == null) {
            return;
        }
        if (!requiredContextId.equals(subnet.getContext().getId())) {
            throw new ConflictException("Le sous-reseau indique n'appartient pas au contexte actif.");
        }
    }
}
