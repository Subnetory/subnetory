package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.dto.NetworkContextRequest;
import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.NetworkContextRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class NetworkContextService {

    private final NetworkContextRepository contextRepository;
    private final ContextAccessService contextAccessService;

    public NetworkContextService(NetworkContextRepository contextRepository,
                                 ContextAccessService contextAccessService) {
        this.contextRepository = contextRepository;
        this.contextAccessService = contextAccessService;
    }

    public Page<NetworkContextResponse> findAll(Pageable pageable) {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) {
            return new PageImpl<>(java.util.List.of(), pageable, 0);
        }
        return contextRepository.findByIdIn(allowedIds, pageable).map(this::toResponse);
    }

    public java.util.List<NetworkContextResponse> findAllAccessible() {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return java.util.List.of();
        return contextRepository.findByIdInOrderByNameAsc(allowedIds).stream()
                .map(this::toResponse)
                .toList();
    }

    public NetworkContextResponse findById(Long id) {
        contextAccessService.requireAccess(id);
        return contextRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("NetworkContext", id));
    }

    /** Accès entité brute — utilisé par SiteService / SubnetService. */
    public NetworkContext getEntityById(Long id) {
        contextAccessService.requireAccess(id);
        return contextRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("NetworkContext", id));
    }

    @Transactional
    public NetworkContextResponse create(NetworkContextRequest request) {
        if (contextRepository.existsByName(request.name())) {
            throw new ConflictException("NetworkContext with name '" + request.name() + "' already exists");
        }
        NetworkContext ctx = new NetworkContext();
        ctx.setName(request.name());
        ctx.setDescription(request.description());
        return toResponse(contextRepository.save(ctx));
    }

    @Transactional
    public NetworkContextResponse update(Long id, NetworkContextRequest request) {
        contextAccessService.requireAccess(id);
        NetworkContext ctx = contextRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("NetworkContext", id));
        if (!ctx.getName().equals(request.name()) && contextRepository.existsByName(request.name())) {
            throw new ConflictException("NetworkContext with name '" + request.name() + "' already exists");
        }
        ctx.setName(request.name());
        ctx.setDescription(request.description());
        return toResponse(contextRepository.save(ctx));
    }

    @Transactional
    public void delete(Long id) {
        NetworkContext context = getEntityById(id);
        contextRepository.delete(context);
    }

    // --- mapping ---

    public NetworkContextResponse toResponse(NetworkContext ctx) {
        return new NetworkContextResponse(
                ctx.getId(),
                ctx.getName(),
                ctx.getDescription(),
                ctx.getCreatedAt(),
                ctx.getUpdatedAt()
        );
    }
}
