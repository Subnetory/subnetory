package dev.subnetory.service;

import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.NetworkContextRepository;
import dev.subnetory.repository.UserRepository;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Source de verite des autorisations par contexte. */
@Service
@Transactional(readOnly = true)
public class ContextAccessService {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final UserRepository userRepository;
    private final NetworkContextRepository contextRepository;
    private final AuthAuditService authAuditService;

    public ContextAccessService(UserRepository userRepository,
                                NetworkContextRepository contextRepository,
                                AuthAuditService authAuditService) {
        this.userRepository = userRepository;
        this.contextRepository = contextRepository;
        this.authAuditService = authAuditService;
    }

    /** Retourne uniquement les contextes autorises pour le principal courant. */
    public List<Long> allowedContextIds() {
        Authentication authentication = currentAuthentication();
        if (authentication == null) {
            return List.of();
        }
        if (hasAuthority(authentication, ROLE_ADMIN)) {
            return contextRepository.findAllIds();
        }
        return userRepository.findAllowedContextIds(authentication.getName());
    }

    public boolean canAccess(Long contextId) {
        return contextId != null && allowedContextIds().contains(contextId);
    }

    /** Refuse sans reveler si le contexte existe reellement. */
    public void requireAccess(Long contextId) {
        if (!canAccess(contextId)) {
            String username = currentUsername();
            authAuditService.recordContextAccessDenied(username, contextId);
            throw new ResourceNotFoundException("NetworkContext", contextId);
        }
    }

    /** Refuse une ressource enfant sans exposer l'identifiant de son contexte. */
    public void requireResourceAccess(Long contextId, String resourceType, Object resourceId) {
        if (!canAccess(contextId)) {
            authAuditService.recordContextAccessDenied(currentUsername(), contextId);
            throw new ResourceNotFoundException(resourceType, resourceId);
        }
    }

    public String currentUsername() {
        Authentication authentication = currentAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    public boolean isGlobalAdmin() {
        Authentication authentication = currentAuthentication();
        return authentication != null && hasAuthority(authentication, ROLE_ADMIN);
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return authentication;
    }

    private boolean hasAuthority(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
