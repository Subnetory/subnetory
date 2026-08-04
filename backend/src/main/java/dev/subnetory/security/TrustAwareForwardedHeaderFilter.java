package dev.subnetory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Remplace l'auto-configuration Spring Boot de {@link ForwardedHeaderFilter}
 * (correctif securite MOYENNE, audit externe 04/08/2026).
 *
 * <h3>Le probleme</h3>
 * <p>Activer {@code server.forward-headers-strategy=framework} enregistre
 * {@link ForwardedHeaderFilter} de maniere INCONDITIONNELLE, tres tot dans
 * la chaine de filtres (avant Spring Security). Ce filtre remplace
 * {@code request.getRemoteAddr()} (et {@code isSecure()}/scheme/host/port)
 * par la valeur des en-tetes {@code X-Forwarded-*} pour TOUTE requete, sans
 * jamais verifier qui les a envoyes. Au moment ou {@link ClientIpResolver}
 * s'execute plus loin dans la chaine, la valeur "brute" de
 * {@code getRemoteAddr()} a deja ete ecrasee par une valeur potentiellement
 * forgee par le client lui-meme — rendant sa verification
 * {@code trusted-proxy-cidrs} inoperante : elle compare la valeur
 * revendiquee par le client contre elle-meme, jamais l'adresse TCP reelle
 * du pair.</p>
 *
 * <h3>Le correctif</h3>
 * <p>Ce filtre prend exactement la meme decision de confiance que
 * {@link ClientIpResolver} ({@code subnetory.security.trusted-proxy} +
 * {@code trusted-proxy-cidrs}, meme verification CIDR/IP exacte, voir
 * {@link ClientIpResolver#isRequestFromTrustedProxy}), mais AVANT que quoi
 * que ce soit d'autre ne touche la requete : {@code getRemoteAddr()} est
 * encore l'adresse TCP reelle a cet instant, puisque ce filtre est
 * enregistre au meme rang que l'aurait ete l'auto-configuration qu'il
 * remplace (voir {@code SecurityConfig#trustAwareForwardedHeaderFilterRegistration},
 * {@code Ordered.HIGHEST_PRECEDENCE}).</p>
 *
 * <p>Seules les connexions directes provenant d'une plage declaree
 * beneficient de la reecriture (scheme/host/port/remoteAddr depuis les
 * en-tetes {@code X-Forwarded-*}, deleguee a une instance normale de
 * {@link ForwardedHeaderFilter} — pas de reimplementation maison de cette
 * logique). Toute autre connexion traverse la chaine sans modification :
 * {@code X-Forwarded-*} est alors ignore, comme si l'en-tete n'existait
 * pas.</p>
 *
 * <p>{@code server.forward-headers-strategy} doit rester a {@code none}
 * (defaut de l'application, voir {@code application.yml}) partout ou ce
 * filtre est actif : il remplace entierement l'auto-configuration Spring
 * Boot correspondante, il ne s'y ajoute pas — positionner {@code framework}
 * en plus reactiverait le mecanisme non verifie que ce filtre existe
 * justement pour eviter.</p>
 */
public class TrustAwareForwardedHeaderFilter extends OncePerRequestFilter {

    private final ClientIpResolver clientIpResolver;
    private final ForwardedHeaderFilter delegate = new ForwardedHeaderFilter();

    public TrustAwareForwardedHeaderFilter(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {
        if (clientIpResolver.isRequestFromTrustedProxy(request.getRemoteAddr())) {
            delegate.doFilter(request, response, filterChain);
        } else {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Meme comportement que {@link ForwardedHeaderFilter} lui-meme : les
     * en-tetes doivent aussi etre pris en compte lors d'une redispatch
     * asynchrone (ex. traitement Servlet async), pas seulement sur la
     * requete initiale.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
