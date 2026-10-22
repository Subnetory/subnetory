package dev.subnetory.service;

import dev.subnetory.domain.Address;
import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesGroupsResponse;
import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.dto.SeriesRowsRequest;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.SeriesGroupsConflictException;
import dev.subnetory.exception.SeriesConflictException;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.util.IpUtils;
import dev.subnetory.util.SeriesPlanner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reservation d'adresses IPv4 en serie avec nommage incremente.
 *
 * <p>Chemin d'ecriture dedie, volontairement distinct de
 * {@link AddressService#bulkUpsert} (import, scans) : ici la creation est
 * <b>atomique</b> (tout ou rien) et une adresse deja utilisee n'est jamais
 * ecrasee ni ignoree silencieusement.</p>
 *
 * <p>{@link #preview} et {@link #create} s'appuient sur le meme calcul
 * ({@link SeriesPlanner#plan}) : l'apercu montre exactement ce qui sera cree.
 * Les controles d'acces au contexte sont ceux de
 * {@link SubnetService#getEntityById}.</p>
 *
 * <p>Concurrence : le pre-controle de conflits laisse une fenetre de course
 * avec une creation simultanee ; le filet de securite est la contrainte
 * {@code uq_addresses_address_subnet} (V11), dont la violation annule toute la
 * serie.</p>
 */
@Service
@Transactional(readOnly = true)
public class SeriesReservationService {

    private static final String SOURCE = "manual";
    private static final String MODE_ROWS = "rows";
    private static final String STATUS_AVAILABLE = SeriesPlanner.Status.AVAILABLE.name();
    private static final String STATUS_INVALID = "INVALID";
    private static final String STATUS_DUPLICATE = "DUPLICATE";

    private final SubnetService subnetService;
    private final AddressRepository addressRepository;

    public SeriesReservationService(SubnetService subnetService,
                                    AddressRepository addressRepository) {
        this.subnetService = subnetService;
        this.addressRepository = addressRepository;
    }

    /** Calcule la serie et signale les conflits, sans rien enregistrer. */
    public SeriesReservationResponse preview(SeriesReservationRequest request) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        SeriesPlanner.Plan plan = buildPlan(subnet, request);
        return toResponse(subnet, request, plan);
    }

    /**
     * Cree toute la serie dans une seule transaction.
     *
     * @throws SeriesConflictException si la serie ne peut pas etre creee telle quelle
     *                                 (rien n'est cree)
     * @throws InvalidSeriesException  si les parametres sont invalides
     * @throws ConflictException       si une adresse a ete reservee en parallele
     *                                 (rien n'est cree)
     */
    @Transactional
    public SeriesReservationResponse create(SeriesReservationRequest request, String currentUser) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        SeriesPlanner.Plan plan = buildPlan(subnet, request);
        SeriesReservationResponse view = toResponse(subnet, request, plan);

        if (!plan.canCreate()) {
            throw new SeriesConflictException(conflictMessage(plan), view);
        }

        List<Address> addresses = plan.rows().stream()
                .map(row -> toEntity(subnet, request, row, currentUser))
                .toList();
        try {
            addressRepository.saveAllAndFlush(addresses);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "An address of the series was reserved concurrently; no address was created.");
        }
        return view;
    }

    // -------------------------------------------------------
    // Series enchainees (groupes)
    // -------------------------------------------------------

    /** Nombre maximal de groupes d'une demande. */
    public static final int MAX_GROUPS = 10;

    private static final long IPV4_MAX = 0xFFFFFFFFL;

    /** Calcule les groupes enchaines et signale les conflits, sans rien enregistrer. */
    public SeriesGroupsResponse previewGroups(SeriesGroupsRequest request) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        return toGroupsResponse(subnet, request, planGroups(subnet, request));
    }

    /**
     * Cree tous les groupes dans une seule transaction (tout ou rien), eventuellement
     * dans plusieurs sous-reseaux d'un meme contexte. Une adresse existante n'est jamais
     * modifiee.
     *
     * @throws SeriesGroupsConflictException si un groupe ne peut pas etre cree tel quel
     *                                       (rien n'est cree)
     * @throws InvalidSeriesException        si les parametres sont invalides
     * @throws ConflictException             si une adresse a ete reservee en parallele
     *                                       (rien n'est cree)
     */
    @Transactional
    public SeriesGroupsResponse createGroups(SeriesGroupsRequest request, String currentUser) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        List<GroupPlan> plans = planGroups(subnet, request);
        SeriesGroupsResponse view = toGroupsResponse(subnet, request, plans);

        if (!view.canCreate()) {
            long conflicts = view.groups().stream()
                    .flatMap(g -> g.rows().stream())
                    .filter(r -> !STATUS_AVAILABLE.equals(r.status()))
                    .count();
            int missing = plans.stream().mapToInt(gp -> gp.plan().shortfall()).sum();
            throw new SeriesGroupsConflictException(missing > 0
                    ? "Not enough free addresses for the series: " + missing
                            + " address(es) missing; no address was created."
                    : "The series cannot be created: " + conflicts
                            + " address(es) are occupied, reserved, duplicated or out of range;"
                            + " no address was created.",
                    view);
        }

        List<Address> addresses = new ArrayList<>();
        for (GroupPlan gp : plans) {
            for (SeriesPlanner.Row row : gp.plan().rows()) {
                addresses.add(toEntity(gp.subnet(), gp.request(), row, currentUser));
            }
        }
        try {
            addressRepository.saveAllAndFlush(addresses);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "An address of the series was reserved concurrently; no address was created.");
        }
        return view;
    }

    /**
     * Un groupe et son calcul ; {@code request} est la serie equivalente a partir de son IP
     * de depart, {@code subnet} son sous-reseau.
     */
    private record GroupPlan(SeriesGroupsRequest.Group group, Subnet subnet,
                             SeriesReservationRequest request, SeriesPlanner.Plan plan) {}

    private List<GroupPlan> planGroups(Subnet defaultSubnet, SeriesGroupsRequest request) {
        List<SeriesGroupsRequest.Group> groups = request.groups();
        if (groups == null || groups.isEmpty()) {
            throw new InvalidSeriesException("At least one group is required.");
        }
        if (groups.size() > MAX_GROUPS) {
            throw new InvalidSeriesException("At most " + MAX_GROUPS + " groups are allowed.");
        }
        int total = 0;
        for (SeriesGroupsRequest.Group group : groups) {
            if (group == null || group.count() == null) {
                throw new InvalidSeriesException("Every group needs a count.");
            }
            total += group.count();
        }
        if (total > SeriesPlanner.MAX_COUNT) {
            throw new InvalidSeriesException(
                    "At most " + SeriesPlanner.MAX_COUNT + " addresses can be reserved at once.");
        }
        int gap = request.gap() == null ? 0 : request.gap();
        if (gap < 0 || gap > SeriesPlanner.MAX_STEP) {
            throw new InvalidSeriesException(
                    "The gap must be between 0 and " + SeriesPlanner.MAX_STEP + ".");
        }

        Map<Long, Subnet> subnets = new HashMap<>();
        subnets.put(defaultSubnet.getId(), defaultSubnet);
        // Adresses deja prevues par les groupes precedents, par sous-reseau (choix automatique).
        Map<Long, Set<Long>> planned = new HashMap<>();

        List<GroupPlan> plans = new ArrayList<>(groups.size());
        String cursor = request.startIp() == null ? null : request.startIp().trim();
        Long cursorSubnetId = defaultSubnet.getId();
        for (int i = 0; i < groups.size(); i++) {
            SeriesGroupsRequest.Group group = groups.get(i);
            Subnet subnet = resolveSubnet(group.subnetId(), defaultSubnet, subnets);

            String start;
            if (group.startIp() != null) {
                start = group.startIp().trim();
            } else if (cursor != null && subnet.getId().equals(cursorSubnetId)) {
                start = cursor;
            } else {
                start = firstFreeStart(subnet, group, request.occupiedMode(),
                        planned.getOrDefault(subnet.getId(), Set.of()));
            }
            SeriesReservationRequest single = new SeriesReservationRequest(
                    subnet.getId(), start, group.count(), group.step(), group.prefix(),
                    group.startCounter(), group.counterWidth(), group.suffix(),
                    request.occupiedMode(), request.description(), request.temporary());
            SeriesPlanner.Plan plan = buildPlan(subnet, single);
            plans.add(new GroupPlan(group, subnet, single, plan));

            Set<Long> subnetPlanned = planned.computeIfAbsent(subnet.getId(), k -> new HashSet<>());
            for (SeriesPlanner.Row row : plan.rows()) {
                subnetPlanned.add(IpUtils.ipv4ToLong(row.ip()));
            }

            if (!plan.rows().isEmpty()) {
                String last = plan.rows().get(plan.rows().size() - 1).ip();
                long next = IpUtils.ipv4ToLong(last) + 1L + gap;
                if (next > IPV4_MAX) {
                    if (i < groups.size() - 1) {
                        throw new InvalidSeriesException(
                                "The chained series go beyond the IPv4 address space.");
                    }
                } else {
                    cursor = IpUtils.longToIpv4(next);
                    cursorSubnetId = subnet.getId();
                }
            } else {
                cursor = start;
                cursorSubnetId = subnet.getId();
            }
        }
        return plans;
    }

    /**
     * Sous-reseau d'un groupe ou d'une ligne : le sien s'il en a un (controle d'acces habituel,
     * meme contexte que le sous-reseau de la demande), sinon celui de la demande.
     */
    private Subnet resolveSubnet(Long subnetId, Subnet defaultSubnet, Map<Long, Subnet> cache) {
        if (subnetId == null) {
            return defaultSubnet;
        }
        Subnet cached = cache.get(subnetId);
        if (cached != null) {
            return cached;
        }
        Subnet subnet = subnetService.getEntityById(subnetId);
        if (!Objects.equals(contextId(subnet), contextId(defaultSubnet))) {
            throw new InvalidSeriesException("All subnets of a reservation must belong to the same context.");
        }
        cache.put(subnet.getId(), subnet);
        return subnet;
    }

    private static Long contextId(Subnet subnet) {
        return subnet.getContext() == null ? null : subnet.getContext().getId();
    }

    /**
     * Premiere adresse de depart libre du sous-reseau pour un groupe : en mode {@code block}, le
     * premier bloc de {@code count} adresses (espacees de {@code step}) toutes libres ; en mode
     * {@code skip}, la premiere adresse libre. Passerelle, adresses existantes et adresses deja
     * prevues par les groupes precedents sont exclues.
     */
    private String firstFreeStart(Subnet subnet, SeriesGroupsRequest.Group group, String mode,
                                  Set<Long> planned) {
        Bounds bounds = bounds(subnet);
        Set<Long> blocked = new HashSet<>(planned);
        for (String ip : addressRepository.findOccupiedIpsBetween(
                subnet.getId(), IpUtils.longToIpv4(bounds.firstUsable()),
                IpUtils.longToIpv4(bounds.lastUsable()))) {
            blocked.add(IpUtils.ipv4ToLong(ip));
        }
        if (bounds.gateway() != null) {
            blocked.add(bounds.gateway());
        }

        boolean skip = "skip".equals(mode);
        int count = skip ? 1 : group.count();
        long step = group.step() == null ? 1 : group.step();
        long start = bounds.firstUsable();
        while (start + (count - 1) * step <= bounds.lastUsable()) {
            int blockedAt = -1;
            for (int k = 0; k < count; k++) {
                if (blocked.contains(start + k * step)) {
                    blockedAt = k;
                    break;
                }
            }
            if (blockedAt < 0) {
                return IpUtils.longToIpv4(start);
            }
            start = step == 1 ? start + blockedAt + 1 : start + 1;
        }
        throw new InvalidSeriesException("No free block of " + group.count()
                + " address(es) in subnet " + subnet.getNetwork() + ".");
    }

    private SeriesGroupsResponse toGroupsResponse(Subnet defaultSubnet, SeriesGroupsRequest request,
                                                  List<GroupPlan> plans) {
        // Chevauchement entre groupes : une adresse deja prise par un groupe precedent
        // (meme sous-reseau) est signalee DUPLICATE dans le groupe suivant.
        Set<String> seen = new HashSet<>();
        boolean overlap = false;
        List<SeriesGroupsResponse.Group> groups = new ArrayList<>(plans.size());
        for (GroupPlan gp : plans) {
            List<SeriesReservationResponse.Entry> rows = new ArrayList<>(gp.plan().rows().size());
            for (SeriesPlanner.Row row : gp.plan().rows()) {
                String status = row.status().name();
                if (!seen.add(gp.subnet().getId() + "|" + row.ip())) {
                    status = STATUS_DUPLICATE;
                    overlap = true;
                }
                rows.add(new SeriesReservationResponse.Entry(row.ip(), row.hostname(), status));
            }
            groups.add(new SeriesGroupsResponse.Group(
                    gp.group().label(),
                    gp.group().count(),
                    gp.plan().shortfall(),
                    rows,
                    gp.plan().skipped().stream().map(SeriesReservationService::toEntry).toList(),
                    gp.subnet().getId(),
                    gp.subnet().getNetwork()));
        }
        return new SeriesGroupsResponse(
                defaultSubnet.getNetwork(),
                request.occupiedMode(),
                !overlap && plans.stream().allMatch(gp -> gp.plan().canCreate()),
                plans.stream().mapToInt(gp -> gp.group().count()).sum(),
                groups);
    }

    // -------------------------------------------------------
    // Lignes explicites (serie ajustee ligne par ligne)
    // -------------------------------------------------------

    /**
     * Controle une liste explicite de lignes sans rien enregistrer. Statuts :
     * {@code AVAILABLE}, {@code OCCUPIED}, {@code RESERVED} (passerelle),
     * {@code OUT_OF_RANGE}, {@code INVALID} (IPv4 mal formee) et {@code DUPLICATE}
     * (adresse deja presente plus haut dans la liste).
     */
    public SeriesReservationResponse previewRows(SeriesRowsRequest request) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        Map<Long, Subnet> subnets = new HashMap<>();
        return toRowsResponse(subnet, request, checkRows(subnet, request, subnets));
    }

    /**
     * Cree toutes les lignes dans une seule transaction (tout ou rien). Une adresse
     * existante n'est jamais modifiee. Une ligne peut viser un autre sous-reseau du meme
     * contexte.
     *
     * @throws SeriesConflictException si une ligne n'est pas {@code AVAILABLE} (rien n'est cree)
     * @throws InvalidSeriesException  si la liste est invalide (vide, trop longue, nom trop long)
     * @throws ConflictException       si une adresse a ete reservee en parallele (rien n'est cree)
     */
    @Transactional
    public SeriesReservationResponse createRows(SeriesRowsRequest request, String currentUser) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        Map<Long, Subnet> subnets = new HashMap<>();
        List<String> statuses = checkRows(subnet, request, subnets);
        SeriesReservationResponse view = toRowsResponse(subnet, request, statuses);

        if (!view.canCreate()) {
            long conflicts = statuses.stream().filter(st -> !STATUS_AVAILABLE.equals(st)).count();
            throw new SeriesConflictException("The reservation cannot be created: " + conflicts
                    + " address(es) are occupied, reserved, duplicated, invalid or out of range;"
                    + " no address was created.", view);
        }

        List<Address> addresses = new ArrayList<>();
        for (SeriesRowsRequest.Row row : request.rows()) {
            addresses.add(newAddress(resolveSubnet(row.subnetId(), subnet, subnets),
                    normalizedIp(row.ip()), row.hostname(),
                    row.description(), row.mac(), Boolean.TRUE.equals(row.temporary()), currentUser));
        }
        try {
            addressRepository.saveAllAndFlush(addresses);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(
                    "An address of the series was reserved concurrently; no address was created.");
        }
        return view;
    }

    private List<String> checkRows(Subnet defaultSubnet, SeriesRowsRequest request,
                                   Map<Long, Subnet> subnets) {
        List<SeriesRowsRequest.Row> rows = request.rows();
        if (rows == null || rows.isEmpty()) {
            throw new InvalidSeriesException("At least one row is required.");
        }
        if (rows.size() > SeriesPlanner.MAX_COUNT) {
            throw new InvalidSeriesException(
                    "At most " + SeriesPlanner.MAX_COUNT + " rows are allowed.");
        }
        for (SeriesRowsRequest.Row row : rows) {
            if (row == null) {
                throw new InvalidSeriesException("A row must not be empty.");
            }
            if (row.hostname() != null && row.hostname().length() > SeriesPlanner.MAX_HOSTNAME_LENGTH) {
                throw new InvalidSeriesException("Hostname must be at most "
                        + SeriesPlanner.MAX_HOSTNAME_LENGTH + " characters.");
            }
        }

        subnets.put(defaultSubnet.getId(), defaultSubnet);
        List<Subnet> rowSubnets = new ArrayList<>(rows.size());
        Map<Long, Bounds> boundsBySubnet = new HashMap<>();
        for (SeriesRowsRequest.Row row : rows) {
            Subnet subnet = resolveSubnet(row.subnetId(), defaultSubnet, subnets);
            rowSubnets.add(subnet);
            boundsBySubnet.computeIfAbsent(subnet.getId(), k -> bounds(subnet));
        }

        // Une seule requete de conflits par sous-reseau (bornes = min/max des lignes valides).
        Map<Long, long[]> minMax = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String ip = normalizedIp(rows.get(i).ip());
            if (!IpUtils.isValidIpv4(ip)) {
                continue;
            }
            Long subnetId = rowSubnets.get(i).getId();
            Bounds bounds = boundsBySubnet.get(subnetId);
            long value = IpUtils.ipv4ToLong(ip);
            if (value >= bounds.firstUsable() && value <= bounds.lastUsable()) {
                long[] range = minMax.computeIfAbsent(subnetId,
                        k -> new long[] {Long.MAX_VALUE, Long.MIN_VALUE});
                range[0] = Math.min(range[0], value);
                range[1] = Math.max(range[1], value);
            }
        }
        Map<Long, Set<Long>> occupiedBySubnet = new HashMap<>();
        for (Map.Entry<Long, long[]> entry : minMax.entrySet()) {
            Set<Long> occupied = new HashSet<>();
            for (String ip : addressRepository.findOccupiedIpsBetween(
                    entry.getKey(), IpUtils.longToIpv4(entry.getValue()[0]),
                    IpUtils.longToIpv4(entry.getValue()[1]))) {
                occupied.add(IpUtils.ipv4ToLong(ip));
            }
            occupiedBySubnet.put(entry.getKey(), occupied);
        }

        List<String> statuses = new ArrayList<>(rows.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String ip = normalizedIp(rows.get(i).ip());
            if (!IpUtils.isValidIpv4(ip)) {
                statuses.add(STATUS_INVALID);
                continue;
            }
            Long subnetId = rowSubnets.get(i).getId();
            Bounds bounds = boundsBySubnet.get(subnetId);
            long value = IpUtils.ipv4ToLong(ip);
            if (!seen.add(subnetId + "|" + value)) {
                statuses.add(STATUS_DUPLICATE);
            } else if (value < bounds.firstUsable() || value > bounds.lastUsable()) {
                statuses.add(SeriesPlanner.Status.OUT_OF_RANGE.name());
            } else if (bounds.gateway() != null && bounds.gateway() == value) {
                statuses.add(SeriesPlanner.Status.RESERVED.name());
            } else if (occupiedBySubnet.getOrDefault(subnetId, Set.of()).contains(value)) {
                statuses.add(SeriesPlanner.Status.OCCUPIED.name());
            } else {
                statuses.add(STATUS_AVAILABLE);
            }
        }
        return statuses;
    }

    private SeriesReservationResponse toRowsResponse(Subnet subnet, SeriesRowsRequest request,
                                                     List<String> statuses) {
        List<SeriesReservationResponse.Entry> entries = new ArrayList<>(statuses.size());
        for (int i = 0; i < statuses.size(); i++) {
            SeriesRowsRequest.Row row = request.rows().get(i);
            entries.add(new SeriesReservationResponse.Entry(
                    normalizedIp(row.ip()), row.hostname(), statuses.get(i)));
        }
        return new SeriesReservationResponse(
                subnet.getNetwork(), MODE_ROWS,
                statuses.stream().allMatch(STATUS_AVAILABLE::equals),
                request.rows().size(), 0, entries, List.of());
    }

    /** IP rognee ; une IPv4 valide est ramenee a sa forme canonique ("010.0.0.1" -> "10.0.0.1"). */
    private static String normalizedIp(String ip) {
        String trimmed = ip == null ? "" : ip.trim();
        return IpUtils.isValidIpv4(trimmed)
                ? IpUtils.longToIpv4(IpUtils.ipv4ToLong(trimmed))
                : trimmed;
    }

    // -------------------------------------------------------
    // Calcul
    // -------------------------------------------------------

    private SeriesPlanner.Plan buildPlan(Subnet subnet, SeriesReservationRequest request) {
        SeriesPlanner.Spec spec = request.toSpec();
        SeriesPlanner.validate(spec);

        Bounds bounds = bounds(subnet);
        long firstUsable = bounds.firstUsable();
        long lastUsable = bounds.lastUsable();

        long start = IpUtils.ipv4ToLong(spec.startIp());
        long windowEnd = spec.mode() == SeriesPlanner.OccupiedMode.SKIP
                ? lastUsable
                : Math.min(lastUsable, start + (long) (spec.count() - 1) * spec.step());

        Set<Long> occupied = new HashSet<>();
        if (start <= windowEnd) {
            for (String ip : addressRepository.findOccupiedIpsBetween(
                    subnet.getId(), spec.startIp(), IpUtils.longToIpv4(windowEnd))) {
                occupied.add(IpUtils.ipv4ToLong(ip));
            }
        }

        SeriesPlanner.Window window = new SeriesPlanner.Window(
                firstUsable, lastUsable, bounds.gateway(), occupied);
        return SeriesPlanner.plan(spec, window);
    }

    /** Plage attribuable d'un sous-reseau IPv4 et passerelle eventuelle. */
    private record Bounds(long firstUsable, long lastUsable, Long gateway) {}

    private Bounds bounds(Subnet subnet) {
        String cidr = subnet.getNetwork();
        if (!IpUtils.isValidCidr(cidr)) {
            throw new InvalidSeriesException("Series reservation supports IPv4 subnets only.");
        }
        int prefixLength = IpUtils.cidrPrefixLength(cidr);
        long networkValue = IpUtils.ipv4ToLong(IpUtils.networkAddress(cidr));
        long broadcastValue = IpUtils.ipv4ToLong(IpUtils.broadcastAddress(cidr));
        // Meme regle que AddressRepository#findAvailableIps : network et broadcast
        // exclus jusqu'au /30 ; /31 et /32 gardent toutes leurs adresses.
        long firstUsable = prefixLength <= 30 ? networkValue + 1 : networkValue;
        long lastUsable = prefixLength <= 30 ? broadcastValue - 1 : broadcastValue;
        return new Bounds(firstUsable, lastUsable, gatewayValue(subnet, networkValue, broadcastValue));
    }

    private Long gatewayValue(Subnet subnet, long networkValue, long broadcastValue) {
        String gateway = subnet.getGateway();
        if (gateway == null || gateway.isBlank()) {
            return null;
        }
        int slash = gateway.indexOf('/');
        String ip = (slash >= 0 ? gateway.substring(0, slash) : gateway).trim();
        if (!IpUtils.isValidIpv4(ip)) {
            return null;
        }
        long value = IpUtils.ipv4ToLong(ip);
        return value >= networkValue && value <= broadcastValue ? value : null;
    }

    // -------------------------------------------------------
    // Mapping
    // -------------------------------------------------------

    private Address toEntity(Subnet subnet, SeriesReservationRequest request,
                             SeriesPlanner.Row row, String currentUser) {
        return newAddress(subnet, row.ip(), row.hostname(), request.description(), null,
                Boolean.TRUE.equals(request.temporary()), currentUser);
    }

    private Address newAddress(Subnet subnet, String ip, String hostname, String description,
                               String mac, boolean temporary, String currentUser) {
        Address address = new Address();
        address.setAddress(ip);
        address.setHostname(hostname == null || hostname.isBlank() ? null : hostname);
        address.setDescription(description == null || description.isBlank() ? null : description);
        address.setMac(mac == null || mac.isBlank() ? null : mac.trim().toLowerCase(Locale.ROOT));
        address.setContext(subnet.getContext());
        address.setSite(subnet.getSite());
        address.setSubnet(subnet);
        address.setModifiedBy(currentUser);
        address.setTemporary(temporary);
        address.setDiscoverySource(SOURCE);
        return address;
    }

    private SeriesReservationResponse toResponse(Subnet subnet, SeriesReservationRequest request,
                                                 SeriesPlanner.Plan plan) {
        return new SeriesReservationResponse(
                subnet.getNetwork(),
                request.toSpec().mode().name().toLowerCase(Locale.ROOT),
                plan.canCreate(),
                request.count() == null ? 0 : request.count(),
                plan.shortfall(),
                plan.rows().stream().map(SeriesReservationService::toEntry).toList(),
                plan.skipped().stream().map(SeriesReservationService::toEntry).toList());
    }

    private static SeriesReservationResponse.Entry toEntry(SeriesPlanner.Row row) {
        return new SeriesReservationResponse.Entry(row.ip(), row.hostname(), row.status().name());
    }

    private static String conflictMessage(SeriesPlanner.Plan plan) {
        long conflicts = plan.rows().stream()
                .filter(r -> r.status() != SeriesPlanner.Status.AVAILABLE)
                .count();
        if (plan.shortfall() > 0) {
            return "Not enough free addresses for the series: " + plan.shortfall()
                    + " address(es) missing; no address was created.";
        }
        return "The series cannot be created: " + conflicts
                + " address(es) are occupied, reserved or out of range; no address was created.";
    }
}
