package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SiteResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.SiteRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SiteService {

    private final SiteRepository siteRepository;
    private final NetworkContextService contextService;
    private final ContextAccessService contextAccessService;

    public SiteService(SiteRepository siteRepository,
                       NetworkContextService contextService,
                       ContextAccessService contextAccessService) {
        this.siteRepository = siteRepository;
        this.contextService = contextService;
        this.contextAccessService = contextAccessService;
    }

    public Page<SiteResponse> findAll(Pageable pageable) {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return Page.empty(pageable);
        return siteRepository.findByContextIdIn(allowedIds, pageable).map(this::toResponse);
    }

    public Page<SiteResponse> findByContext(Long contextId, Pageable pageable) {
        contextAccessService.requireAccess(contextId);
        return siteRepository.findByContextId(contextId, pageable).map(this::toResponse);
    }

    public SiteResponse findById(Long id) {
        return toResponse(getEntityById(id));
    }

    public Site getEntityById(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Site", id));
        contextAccessService.requireResourceAccess(site.getContext().getId(), "Site", id);
        return site;
    }

    @Transactional
    public SiteResponse create(SiteRequest request) {
        if (siteRepository.existsByCode(request.code())) {
            throw new ConflictException("Site with code '" + request.code() + "' already exists");
        }
        NetworkContext context = contextService.getEntityById(request.contextId());
        Site site = new Site();
        site.setName(request.name());
        site.setCode(request.code().toUpperCase());
        site.setContext(context);
        return toResponse(siteRepository.save(site));
    }

    @Transactional
    public SiteResponse update(Long id, SiteRequest request) {
        Site site = getEntityById(id);
        // Vérifier unicité du code uniquement si changement
        if (!site.getCode().equals(request.code()) && siteRepository.existsByCode(request.code())) {
            throw new ConflictException("Site with code '" + request.code() + "' already exists");
        }
        NetworkContext context = contextService.getEntityById(request.contextId());
        site.setName(request.name());
        site.setCode(request.code().toUpperCase());
        site.setContext(context);
        return toResponse(siteRepository.save(site));
    }

    @Transactional
    public void delete(Long id) {
        Site site = getEntityById(id);
        siteRepository.delete(site);
    }

    // --- mapping ---

    public SiteResponse toResponse(Site site) {
        return new SiteResponse(
                site.getId(),
                site.getName(),
                site.getCode(),
                site.getContext().getId(),
                site.getContext().getName(),
                site.getCreatedAt(),
                site.getUpdatedAt()
        );
    }
}
