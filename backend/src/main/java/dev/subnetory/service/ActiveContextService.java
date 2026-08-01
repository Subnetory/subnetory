package dev.subnetory.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Conserve le contexte actif dans la session Web, sans en faire une autorisation. */
@Service
public class ActiveContextService {

    static final String SESSION_KEY = "subnetory.activeContextId";

    private final ContextAccessService contextAccessService;

    public ActiveContextService(ContextAccessService contextAccessService) {
        this.contextAccessService = contextAccessService;
    }

    public Long get(HttpSession session) {
        Object value = session.getAttribute(SESSION_KEY);
        if (!(value instanceof Long contextId)) return null;
        if (!contextAccessService.canAccess(contextId)) {
            session.removeAttribute(SESSION_KEY);
            return null;
        }
        return contextId;
    }

    public Long resolve(HttpSession session, Long requestedContextId) {
        if (requestedContextId != null) {
            select(session, requestedContextId);
            return requestedContextId;
        }
        return get(session);
    }

    public void select(HttpSession session, Long contextId) {
        contextAccessService.requireAccess(contextId);
        session.setAttribute(SESSION_KEY, contextId);
    }

    public void reset(HttpSession session) {
        session.removeAttribute(SESSION_KEY);
    }

    /** Contexte actif de la requête Web courante, utile aux modèles de formulaire. */
    public Long getCurrentRequestContext() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpSession session = attrs.getRequest().getSession(false);
        return session == null ? null : get(session);
    }
}
