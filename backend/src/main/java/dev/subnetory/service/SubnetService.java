package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.domain.Subnet;
import dev.subnetory.domain.Vlan;
import dev.subnetory.dto.SubnetRequest;
import dev.subnetory.dto.SubnetResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.SubnetRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SubnetService {

    private final SubnetRepository subnetRepository;
    private final NetworkContextService contextService;
    private final SiteService siteService;
    private final VlanService vlanService;
    private final ContextAccessService contextAccessService;

    public SubnetService(SubnetRepository subnetRepository,
                         NetworkContextService contextService,
                         SiteService siteService,
                         VlanService vlanService,
                         ContextAccessService contextAccessService) {
        this.subnetRepository = subnetRepository;
        this.contextService = contextService;
        this.siteService = siteService;
        this.vlanService = vlanService;
        this.contextAccessService = contextAccessService;
    }

    // -------------------------------------------------------
    // Lecture paginée (GUI, API listée)
    // -------------------------------------------------------

    public Page<SubnetResponse> findAll(Pageable pageable) {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return Page.empty(pageable);
        return subnetRepository.findByContextIdIn(allowedIds, pageable).map(this::toResponse);
    }

    public Page<SubnetResponse> findBySite(Long siteId, Pageable pageable) {
        siteService.getEntityById(siteId);
        return subnetRepository.findBySiteId(siteId, pageable).map(this::toResponse);
    }

    public Page<SubnetResponse> findByContext(Long contextId, Pageable pageable) {
        contextAccessService.requireAccess(contextId);
        return subnetRepository.findByContextId(contextId, pageable).map(this::toResponse);
    }

    /** Navigation drill-down VLAN → subnets (audit du 31/07/2026). */
    public Page<SubnetResponse> findByVlan(Long vlanId, Pageable pageable) {
        vlanService.getEntityById(vlanId);
        return subnetRepository.findByVlanId(vlanId, pageable).map(this::toResponse);
    }

    public SubnetResponse findById(Long id) {
        return toResponse(getEntityById(id));
    }

    // -------------------------------------------------------
    // Lecture non-paginée — export CSV (Sprint 2.8)
    // -------------------------------------------------------

    /**
     * Retourne tous les subnets pour l'export CSV, sans pagination.
     *
     * <p>Les filtres {@code siteId} et {@code contextId} sont mutuellement exclusifs.
     * Si {@code siteId} est fourni, il est prioritaire sur {@code contextId}.
     * Si aucun filtre n'est fourni, tous les subnets sont retournés.</p>
     *
     * <p>Utilisé par {@code GET /api/v1/subnets/export/csv}.</p>
     */
    public List<SubnetResponse> findAllForExport(Long siteId, Long contextId) {
        if (siteId != null) {
            siteService.getEntityById(siteId);
            return subnetRepository.findBySiteId(siteId).stream()
                    .map(this::toResponse)
                    .toList();
        }
        if (contextId != null) {
            contextAccessService.requireAccess(contextId);
            return subnetRepository.findByContextId(contextId).stream()
                    .map(this::toResponse)
                    .toList();
        }
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return List.of();
        return subnetRepository.findByContextIdIn(allowedIds).stream()
                .map(this::toResponse)
                .toList();
    }

    // -------------------------------------------------------
    // Résolution d'entité (utilisée par les autres services)
    // -------------------------------------------------------

    /**
     * Recherche tous les subnets correspondant exactement à un réseau CIDR.
     * Peut retourner plusieurs résultats si le même réseau existe dans plusieurs contextes (VRF).
     * Utilisé par l'import CSV pour la résolution subnet_network → subnet_id.
     */
    public List<Subnet> findAllByNetwork(String network) {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return List.of();
        return subnetRepository.findAllByNetworkAndContextIds(network, allowedIds);
    }

    public Subnet getEntityById(Long id) {
        Subnet subnet = subnetRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Subnet", id));
        contextAccessService.requireResourceAccess(subnet.getContext().getId(), "Subnet", id);
        return subnet;
    }

    public Optional<Subnet> findEntityByIdOptional(Long id) {
        return subnetRepository.findById(id)
                .filter(subnet -> contextAccessService.canAccess(subnet.getContext().getId()));
    }

    // -------------------------------------------------------
    // Écriture
    // -------------------------------------------------------

    @Transactional
    public SubnetResponse create(SubnetRequest request) {
        Subnet subnet = buildSubnet(new Subnet(), request);
        if (subnetRepository.existsByNetworkCidrAndSiteId(request.network(), request.siteId())) {
            throw new ConflictException(
                    "Subnet " + request.network() + " already exists on site " + request.siteId());
        }
        return toResponse(subnetRepository.save(subnet));
    }

    @Transactional
    public SubnetResponse update(Long id, SubnetRequest request) {
        Subnet subnet = getEntityById(id);
        String originalNetwork = subnet.getNetwork();
        Long originalSiteId = subnet.getSite().getId();
        Subnet updated = buildSubnet(subnet, request);

        // Unicité : vérifier seulement si le réseau ou le site change
        if ((!originalNetwork.equals(request.network())
                || !originalSiteId.equals(request.siteId()))
                && subnetRepository.existsByNetworkCidrAndSiteId(request.network(), request.siteId())) {
            throw new ConflictException(
                    "Subnet " + request.network() + " already exists on site " + request.siteId());
        }
        return toResponse(subnetRepository.save(updated));
    }

    @Transactional
    public void delete(Long id) {
        Subnet subnet = getEntityById(id);
        subnetRepository.delete(subnet);
    }

    // -------------------------------------------------------
    // Helpers privés
    // -------------------------------------------------------

    private Subnet buildSubnet(Subnet subnet, SubnetRequest request) {
        NetworkContext context = contextService.getEntityById(request.contextId());
        Site site = siteService.getEntityById(request.siteId());

        if (!site.getContext().getId().equals(context.getId())) {
            throw new ConflictException("Le site selectionne n'appartient pas au contexte choisi.");
        }

        subnet.setNetwork(request.network());
        subnet.setDescription(request.description());
        subnet.setGateway(request.gateway() != null && request.gateway().isBlank()
                ? null : request.gateway());
        subnet.setContext(context);
        subnet.setSite(site);

        if (request.vlanId() != null) {
            Vlan vlan = vlanService.getEntityById(request.vlanId());
            if (!vlan.getSite().getId().equals(site.getId())) {
                throw new ConflictException("Le VLAN selectionne n'appartient pas au site choisi.");
            }
            subnet.setVlan(vlan);
        } else {
            subnet.setVlan(null);
        }

        if (request.parentId() != null) {
            Subnet parent = getEntityById(request.parentId());
            if (!parent.getContext().getId().equals(context.getId())) {
                throw new ConflictException("Le sous-reseau parent n'appartient pas au contexte choisi.");
            }
            subnet.setParent(parent);
        } else {
            subnet.setParent(null);
        }
        return subnet;
    }

    // -------------------------------------------------------
    // Mapping
    // -------------------------------------------------------

    public SubnetResponse toResponse(Subnet s) {
        return new SubnetResponse(
                s.getId(),
                s.getNetwork(),
                s.getDescription(),
                s.getGateway(),
                s.getContext().getId(),
                s.getContext().getName(),
                s.getSite().getId(),
                s.getSite().getName(),
                s.getVlan() != null ? s.getVlan().getId() : null,
                s.getVlan() != null ? s.getVlan().getName() : null,
                s.getParent() != null ? s.getParent().getId() : null,
                s.getParent() != null ? s.getParent().getNetwork() : null,
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
