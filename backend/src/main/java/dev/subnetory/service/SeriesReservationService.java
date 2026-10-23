package dev.subnetory.service;

import dev.subnetory.domain.Address;
import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.SeriesConflictException;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.util.IpUtils;
import dev.subnetory.util.SeriesPlanner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    // Calcul
    // -------------------------------------------------------

    private SeriesPlanner.Plan buildPlan(Subnet subnet, SeriesReservationRequest request) {
        SeriesPlanner.Spec spec = request.toSpec();
        SeriesPlanner.validate(spec);

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
                firstUsable, lastUsable, gatewayValue(subnet, networkValue, broadcastValue), occupied);
        return SeriesPlanner.plan(spec, window);
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
        Address address = new Address();
        address.setAddress(row.ip());
        address.setHostname(row.hostname());
        String description = request.description();
        address.setDescription(description == null || description.isBlank() ? null : description);
        address.setContext(subnet.getContext());
        address.setSite(subnet.getSite());
        address.setSubnet(subnet);
        address.setModifiedBy(currentUser);
        address.setTemporary(Boolean.TRUE.equals(request.temporary()));
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
