package dev.subnetory.service;

import dev.subnetory.domain.Site;
import dev.subnetory.domain.Vlan;
import dev.subnetory.dto.VlanRequest;
import dev.subnetory.dto.VlanResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.SubnetRepository;
import dev.subnetory.repository.VlanRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class VlanService {

    private final VlanRepository vlanRepository;
    private final SiteService siteService;
    private final ContextAccessService contextAccessService;
    private final SubnetRepository subnetRepository;

    public VlanService(VlanRepository vlanRepository,
                       SiteService siteService,
                       ContextAccessService contextAccessService,
                       SubnetRepository subnetRepository) {
        this.vlanRepository = vlanRepository;
        this.siteService = siteService;
        this.contextAccessService = contextAccessService;
        this.subnetRepository = subnetRepository;
    }

    public Page<VlanResponse> findAll(Pageable pageable) {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return Page.empty(pageable);
        return vlanRepository.findBySiteContextIdIn(allowedIds, pageable).map(this::toResponse);
    }

    public Page<VlanResponse> findBySite(Long siteId, Pageable pageable) {
        siteService.getEntityById(siteId);
        return vlanRepository.findBySiteId(siteId, pageable).map(this::toResponse);
    }

    public Page<VlanResponse> findByContext(Long contextId, Pageable pageable) {
        contextAccessService.requireAccess(contextId);
        return vlanRepository.findBySiteContextId(contextId, pageable).map(this::toResponse);
    }

    public VlanResponse findById(Long id) {
        return toResponse(getEntityById(id));
    }

    public Vlan getEntityById(Long id) {
        Vlan vlan = vlanRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Vlan", id));
        contextAccessService.requireResourceAccess(
                vlan.getSite().getContext().getId(), "Vlan", id);
        return vlan;
    }

    @Transactional
    public VlanResponse create(VlanRequest request) {
        if (vlanRepository.existsByVidAndSiteId(request.vid().shortValue(), request.siteId())) {
            throw new ConflictException(
                    "VLAN " + request.vid() + " already exists on site " + request.siteId());
        }
        Site site = siteService.getEntityById(request.siteId());
        Vlan vlan = new Vlan();
        vlan.setName(request.name());
        vlan.setVid(request.vid().shortValue());
        vlan.setSite(site);
        return toResponse(vlanRepository.save(vlan));
    }

	@Transactional
	public VlanResponse update(Long id, VlanRequest request) {
		Vlan vlan = getEntityById(id);

		Short requestedVid = request.vid().shortValue();
		boolean changed = !Objects.equals(vlan.getVid(), requestedVid)
				|| !Objects.equals(vlan.getSite().getId(), request.siteId());

		if (changed && vlanRepository.existsByVidAndSiteId(requestedVid, request.siteId())) {
			throw new ConflictException(
					"VLAN " + request.vid() + " already exists on site " + request.siteId());
		}

		// Audit 03/08/2026, correctif BLOQUANT : les sous-reseaux stockent
		// leur propre site_id/context_id, jamais resynchronise si le VLAN
		// est deplace vers un autre site apres coup (voir la meme logique
		// sur SiteService#update). Bloque tant qu'une migration explicite
		// du graphe n'est pas implementee.
		if (!Objects.equals(vlan.getSite().getId(), request.siteId())
				&& subnetRepository.existsByVlanId(id)) {
			throw new ConflictException(
					"Impossible de changer le site d'un VLAN qui contient encore des "
							+ "sous-reseaux. Deplacez ou supprimez d'abord ses sous-reseaux.");
		}

		Site site = siteService.getEntityById(request.siteId());
		vlan.setName(request.name());
		vlan.setVid(requestedVid);
		vlan.setSite(site);
		return toResponse(vlanRepository.save(vlan));
	}

    @Transactional
    public void delete(Long id) {
        Vlan vlan = getEntityById(id);
        vlanRepository.delete(vlan);
    }

    // --- mapping ---

    public VlanResponse toResponse(Vlan vlan) {
        return new VlanResponse(
                vlan.getId(),
                vlan.getName(),
                vlan.getVid() != null ? vlan.getVid().intValue() : null,
                vlan.getSite().getId(),
                vlan.getSite().getName(),
                vlan.getCreatedAt(),
                vlan.getUpdatedAt()
        );
    }
}
