package dev.subnetory.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.regex.Pattern;

/**
 * Retour à la liste d'origine (recherche, filtres, page) après une création,
 * une modification ou une suppression.
 *
 * <p>La destination voyage dans le paramètre {@code returnTo}. Elle n'est
 * acceptée que si c'est un chemin interne de l'application sous
 * {@code /network/} (ou la page de recherche {@code /search}) : jamais d'URL absolue, de {@code //}, de barre oblique
 * inverse, de caractère de contrôle ni d'accolade (évite les redirections
 * ouvertes et l'expansion de variables d'URI par la vue de redirection).</p>
 */
public final class ReturnTo {

    static final String PARAM = "returnTo";
    private static final int MAX_LENGTH = 500;
    private static final Pattern SAFE = Pattern.compile("^/(?:network/|search(?:\\?|$))[A-Za-z0-9_\\-./?&=%+~,:@]*$");

    private ReturnTo() {}

    /** Retourne la valeur si elle est sûre, sinon {@code null}. */
    public static String sanitize(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) return null;
        if (value.contains("//") || value.contains("..") || value.contains("\\")) return null;
        return SAFE.matcher(value).matches() ? value : null;
    }

    /** Valeur {@code returnTo} valide de la requête courante, ou {@code null}. */
    public static String fromCurrentRequest() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : sanitize(request.getParameter(PARAM));
    }

    /** URL (chemin + requête) de la page courante, à transmettre aux liens d'édition et de suppression. */
    public static String currentUrl() {
        HttpServletRequest request = currentRequest();
        if (request == null) return null;
        String query = request.getQueryString();
        String url = request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
        return sanitize(url);
    }

    /** Ajoute {@code returnTo} (s'il existe) à l'URL d'action d'un formulaire. */
    public static String withReturnTo(String url) {
        String returnTo = fromCurrentRequest();
        if (returnTo == null) return url;
        return UriComponentsBuilder.fromUriString(url).queryParam(PARAM, "{rt}").build(returnTo).toString();
    }

    /** Lien « Annuler » : la liste d'origine si connue, sinon l'URL par défaut. */
    public static String cancelUrl(String fallback) {
        String returnTo = fromCurrentRequest();
        return returnTo != null ? returnTo : fallback;
    }

    /** Vue de redirection : la liste d'origine si connue, sinon l'URL par défaut. */
    public static String redirect(String fallback) {
        return "redirect:" + cancelUrl(fallback);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }
}
