package dev.subnetory.service;

import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.repository.SubnetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service d'allocation IP.
 *
 * <p>La recherche des premieres IPv4 disponibles est executee directement par
 * PostgreSQL. Le service conserve le contrat applicatif : verification du subnet,
 * plafond de resultats et construction de {@link AvailableIpResponse}.</p>
 */
@Service
@Transactional(readOnly = true)
public class IpAllocService {

    static final int MAX_RESULTS = 50;

    private final SubnetRepository subnetRepository;
    private final AddressRepository addressRepository;
    private final ContextAccessService contextAccessService;

    public IpAllocService(SubnetRepository subnetRepository,
                          AddressRepository addressRepository,
                          ContextAccessService contextAccessService) {
        this.subnetRepository = subnetRepository;
        this.addressRepository = addressRepository;
        this.contextAccessService = contextAccessService;
    }

    /**
     * Retourne les premieres IPs disponibles dans un sous-reseau.
     *
     * @param subnetId identifiant du sous-reseau
     * @param count    nombre d'IPs souhaitees (plafonne a MAX_RESULTS)
     */
    public AvailableIpResponse findAvailableIps(Long subnetId, int count) {
        Subnet subnet = subnetRepository.findById(subnetId)
                .orElseThrow(() -> new ResourceNotFoundException("Subnet", subnetId));
        contextAccessService.requireResourceAccess(
                subnet.getContext().getId(), "Subnet", subnetId);

        int limit = Math.min(count, MAX_RESULTS);
        List<String> available = addressRepository.findAvailableIps(subnetId, limit);

        return new AvailableIpResponse(
                subnet.getNetwork(),
                limit,
                available.size(),
                available);
    }
}
