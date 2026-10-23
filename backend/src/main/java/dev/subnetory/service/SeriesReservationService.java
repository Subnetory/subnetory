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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
     * Cree tous les groupes dans une seule transaction (tout ou rien). Une adresse
     * existante n'est jamais modifiee.
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
                addresses.add(toEntity(subnet, gp.request(), row, currentUser));
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

    /** Un groupe et son calcul ; {@code request} est la serie equivalente a partir de son IP de depart. */
    private record GroupPlan(SeriesGroupsRequest.Group group, SeriesReservationRequest request,
                             SeriesPlanner.Plan plan) {}

    private List<GroupPlan> planGroups(Subnet subnet, SeriesGroupsRequest request) {
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

        List<GroupPlan> plans = new ArrayList<>(groups.size());
        String cursor = request.startIp() == null ? null : request.startIp().trim();
        for (int i = 0; i < groups.size(); i++) {
            SeriesGroupsRequest.Group group = groups.get(i);
            if (group.startIp() != null) {
                cursor = group.startIp().trim();
            }
            SeriesReservationRequest single = new SeriesReservationRequest(
                    subnet.getId(), cursor, group.count(), group.step(), group.prefix(),
                    group.startCounter(), group.counterWidth(), group.suffix(),
                    request.occupiedMode(), request.description(), request.temporary());
            SeriesPlanner.Plan plan = buildPlan(subnet, single);
            plans.add(new GroupPlan(group, single, plan));

            if (!plan.rows().isEmpty() && i < groups.size() - 1) {
                String last = plan.rows().get(plan.rows().size() - 1).ip();
                long next = IpUtils.ipv4ToLong(last) + 1L + gap;
                if (next > IPV4_MAX) {
                    throw new InvalidSeriesException(
                            "The chained series go beyond the IPv4 address space.");
                }
                cursor = IpUtils.longToIpv4(next);
            }
        }
        return plans;
    }

    private SeriesGroupsResponse toGroupsResponse(Subnet subnet, SeriesGroupsRequest request,
                                                  List<GroupPlan> plans) {
        // Chevauchement entre groupes : une adresse deja prise par un groupe precedent
        // est signalee DUPLICATE dans le groupe suivant.
        Set<String> seen = new HashSet<>();
        boolean overlap = false;
        List<SeriesGroupsResponse.Group> groups = new ArrayList<>(plans.size());
        for (GroupPlan gp : plans) {
            List<SeriesReservationResponse.Entry> rows = new ArrayList<>(gp.plan().rows().size());
            for (SeriesPlanner.Row row : gp.plan().rows()) {
                String status = row.status().name();
                if (!seen.add(row.ip())) {
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
                    gp.plan().skipped().stream().map(SeriesReservationService::toEntry).toList()));
        }
        return new SeriesGroupsResponse(
                subnet.getNetwork(),
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
        return toRowsResponse(subnet, request, checkRows(subnet, request));
    }

    /**
     * Cree toutes les lignes dans une seule transaction (tout ou rien). Une adresse
     * existante n'est jamais modifiee.
     *
     * @throws SeriesConflictException si une ligne n'est pas {@code AVAILABLE} (rien n'est cree)
     * @throws InvalidSeriesException  si la liste est invalide (vide, trop longue, nom trop long)
     * @throws ConflictException       si une adresse a ete reservee en parallele (rien n'est cree)
     */
    @Transactional
    public SeriesReservationResponse createRows(SeriesRowsRequest request, String currentUser) {
        Subnet subnet = subnetService.getEntityById(request.subnetId());
        List<String> statuses = checkRows(subnet, request);
        SeriesReservationResponse view = toRowsResponse(subnet, request, statuses);

        if (!view.canCreate()) {
            long conflicts = statuses.stream().filter(st -> !STATUS_AVAILABLE.equals(st)).count();
            throw new SeriesConflictException("The reservation cannot be created: " + conflicts
                    + " address(es) are occupied, reserved, duplicated, invalid or out of range;"
                    + " no address was created.", view);
        }

        List<Address> addresses = new ArrayList<>();
        for (SeriesRowsRequest.Row row : request.rows()) {
            addresses.add(newAddress(subnet, normalizedIp(row.ip()), row.hostname(),
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

    private List<String> checkRows(Subnet subnet, SeriesRowsRequest request) {
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

        Bounds bounds = bounds(subnet);

        // Une seule requete de conflits pour toute la liste (bornes = min/max des lignes valides).
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (SeriesRowsRequest.Row row : rows) {
            String ip = normalizedIp(row.ip());
            if (!IpUtils.isValidIpv4(ip)) {
                continue;
            }
            long value = IpUtils.ipv4ToLong(ip);
            if (value >= bounds.firstUsable() && value <= bounds.lastUsable()) {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        Set<Long> occupied = new HashSet<>();
        if (min <= max) {
            for (String ip : addressRepository.findOccupiedIpsBetween(
                    subnet.getId(), IpUtils.longToIpv4(min), IpUtils.longToIpv4(max))) {
                occupied.add(IpUtils.ipv4ToLong(ip));
            }
        }

        List<String> statuses = new ArrayList<>(rows.size());
        Set<Long> seen = new HashSet<>();
        for (SeriesRowsRequest.Row row : rows) {
            String ip = normalizedIp(row.ip());
            if (!IpUtils.isValidIpv4(ip)) {
                statuses.add(STATUS_INVALID);
                continue;
            }
            long value = IpUtils.ipv4ToLong(ip);
            if (!seen.add(value)) {
                statuses.add(STATUS_DUPLICATE);
            } else if (value < bounds.firstUsable() || value > bounds.lastUsable()) {
                statuses.add(SeriesPlanner.Status.OUT_OF_RANGE.name());
            } else if (bounds.gateway() != null && bounds.gateway() == value) {
                statuses.add(SeriesPlanner.Status.RESERVED.name());
            } else if (occupied.contains(value)) {
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
