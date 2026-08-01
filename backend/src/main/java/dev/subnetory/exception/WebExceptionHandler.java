package dev.subnetory.exception;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Gestionnaire d'erreurs dedie aux controleurs web Thymeleaf
 * ({@code dev.subnetory.web}), en miroir de {@link GlobalExceptionHandler}
 * qui, lui, ne couvre plus que l'API JSON ({@code dev.subnetory.api}) depuis
 * l'audit du 31/07/2026.
 *
 * <p>Avant cette classe, une exception web non interceptee localement par un
 * controleur (via son propre try/catch, comme le fait par exemple
 * {@code AddressWebController.detail()}) partait en JSON brut au lieu des
 * templates {@code error/403.html}, {@code error/404.html},
 * {@code error/500.html}, deja ecrits mais quasiment inatteignables en
 * pratique. Cette classe restaure le comportement attendu pour tout ce qui
 * echappe aux catch locaux existants.
 *
 * <p>Note sur {@link AccessDeniedException} : les refus d'acces issus des
 * regles d'URL de {@code SecurityConfig} (filtre {@code ExceptionTranslationFilter})
 * passaient deja par {@code error/403.html} via {@code accessDeniedHandler()}
 * + {@code response.sendError(403)} (resolu par le mecanisme d'erreur par
 * defaut de Spring Boot). Mais les refus issus de {@code @PreAuthorize} au
 * niveau d'une methode de controleur sont leves DANS le dispatch MVC, avant
 * meme d'atteindre ce filtre -- sans un {@code @ExceptionHandler} explicite
 * ici, ils repartaient donc en JSON via l'ancien {@code GlobalExceptionHandler}
 * non scope. Ce handler couvre desormais les deux cas de la meme facon.
 */
@ControllerAdvice(basePackages = "dev.subnetory.web")
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public String handleNotFound(ResourceNotFoundException ex, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "error/404";
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public String handleNoHandlerFound(NoHandlerFoundException ex, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "error/404";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public String handleAccessDenied(AccessDeniedException ex, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return "error/403";
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public String handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                            HttpServletResponse response) {
        // Signal utile pour reperer d'eventuels pieges POST/redirect
        // similaires a celui corrige le 30/07/2026 (currentRequestPath
        // pointant vers une route POST-only) sans attendre qu'un
        // utilisateur le signale. Pas de template dedie "405" : error/500
        // reste un message generique correct, seul le code HTTP importe
        // vraiment ici (405, pas 500).
        log.warn("405 Method Not Allowed (web): {}", ex.getMessage());
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        return "error/500";
    }

    @ExceptionHandler(Exception.class)
    public String handleGeneric(Exception ex, HttpServletResponse response) {
        log.error("Unexpected error in web controller", ex);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return "error/500";
    }
}
