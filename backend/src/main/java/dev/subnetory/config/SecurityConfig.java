package dev.subnetory.config;

import dev.subnetory.security.ApiRateLimitingFilter;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.LoginRateLimitingFilter;
import dev.subnetory.security.MandatoryPasswordChangeFilter;
import dev.subnetory.security.MfaChallengeFilter;
import dev.subnetory.security.RestoreMaintenanceFilter;
import dev.subnetory.security.RevokedTokenValidator;
import dev.subnetory.security.TrustAwareForwardedHeaderFilter;
import dev.subnetory.security.UserTokenInvalidationValidator;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.security.DynamicLdapAuthenticationProvider;
import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Configuration Spring Security.
 *
 * Quatre chaines :
 * 0. Logout API (/api/v1/auth/logout) : decode manuel, idempotent.
 * 1. API REST (/api/**, /actuator/**) : stateless JWT, CSRF desactive.
 * 2. OpenAPI / Swagger UI : permitAll, CSP assouplie pour Swagger UI.
 * 3. Web Thymeleaf : session, CSRF active, form login.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(LdapProperties.class)
public class SecurityConfig {

    @Value("${subnetory.jwt.secret}")
    private String jwtSecret;

    /**
     * Validation securite (correctif M2) : HS256 exige une cle d'au moins
     * 256 bits (32 octets). Un secret plus court affaiblit la signature.
     * On echoue au demarrage avec un message clair plutot qu'a la premiere
     * emission de token.
     */
    @jakarta.annotation.PostConstruct
    void validateJwtSecret() {
        int bytes = jwtSecret == null
                ? 0
                : jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 32) {
            throw new IllegalStateException(
                    "subnetory.jwt.secret est trop court (" + bytes + " octets). "
                    + "HS256 exige au moins 32 octets (256 bits). "
                    + "Definir un secret d'au moins 32 caracteres ASCII.");
        }
    }

    // -------------------------------------------------------
    // Chaine 0 - Logout API
    // -------------------------------------------------------

    @Bean
    @Order(0)
    public SecurityFilterChain apiLogoutFilterChain(
            HttpSecurity http, RestoreMaintenanceFilter restoreMaintenanceFilter) throws Exception {
        // Le endpoint logout decode manuellement le Bearer token sans le validateur
        // de revocation afin de rester idempotent : deux logout successifs du
        // meme token doivent retourner 204. Tous les autres endpoints API passent
        // par la chaine resource server ci-dessous.
        return http
                .securityMatcher("/api/v1/auth/logout")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(configureSecurityHeaders())
                // Correctif securite MOYENNE (04/08/2026, second audit externe) :
                // cette chaine dediee (Order 0, distincte de apiFilterChain
                // ci-dessous) n'etait pas couverte par RestoreMaintenanceFilter —
                // /api/v1/auth/logout pouvait donc toujours inserer une
                // revocation JWT pendant une restauration en cours.
                // AuthorizationFilter est toujours present dans une chaine
                // Spring Security, quelle que soit sa configuration : ancre
                // stable independamment des filtres realises specifiquement
                // pour cette chaine minimale.
                .addFilterBefore(restoreMaintenanceFilter,
                        org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .build();
    }

    // -------------------------------------------------------
    // Chaine 1 - API REST
    // -------------------------------------------------------

    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                              @Qualifier("jwtDecoder") JwtDecoder jwtDecoder,
                                              ApiRateLimitingFilter apiRateLimitingFilter,
                                              RestoreMaintenanceFilter restoreMaintenanceFilter) throws Exception {
        return http
                .securityMatcher("/api/**", "/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                        // Remplacement du mot de passe temporaire par identifiants :
                        // necessairement accessible sans JWT (le JWT est refuse tant
                        // que mustChangePassword est actif). Meme rate limiting que
                        // /token, authentification complete exigee dans le controller.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/change-password-required").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                )
                .headers(configureSecurityHeaders())
                // Rate limiting generalise (Sprint 2.36 / F7) : positionne avant la
                // validation JWT pour eviter le cout d'un decodage/verification de
                // signature sur une requete deja refusee. Complementaire au rate
                // limiting strict de /api/v1/auth/token (correctif H2).
                .addFilterBefore(apiRateLimitingFilter, BearerTokenAuthenticationFilter.class)
                // Mode maintenance restauration (correctif securite MOYENNE,
                // audit 04/08/2026) : avant le rate limiting, pour ne pas
                // consommer de quota sur des requetes de toute facon refusees
                // tant qu'une restauration est active. Voir RestoreMaintenanceGate.
                .addFilterBefore(restoreMaintenanceFilter, ApiRateLimitingFilter.class)
                .build();
    }

    /**
     * {@code ApiRateLimitingFilter} est un {@code @Component} (donc un bean
     * de type {@code Filter} dans le contexte applicatif) afin de pouvoir
     * beneficier de l'injection de dependances Spring. Sans cette
     * desactivation explicite, Spring Boot l'enregistrerait AUSSI
     * automatiquement comme filtre servlet generique sur {@code /*}
     * (voir {@code ServletContextInitializerBeans}), en plus de son
     * rattachement explicite a {@code apiFilterChain} ci-dessus : le filtre
     * s'executerait alors deux fois par requete et compterait chaque appel
     * en double aupres de {@code ApiRateLimiter}.
     */
    @Bean
    public FilterRegistrationBean<ApiRateLimitingFilter> apiRateLimitingFilterRegistration(
            ApiRateLimitingFilter apiRateLimitingFilter) {
        FilterRegistrationBean<ApiRateLimitingFilter> registration =
                new FilterRegistrationBean<>(apiRateLimitingFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Meme raison que {@code apiRateLimitingFilterRegistration} ci-dessus :
     * {@code RestoreMaintenanceFilter} est un {@code @Component}, donc
     * auto-enregistre par Spring Boot sur {@code /*} en plus de son
     * rattachement explicite a {@code apiFilterChain} et
     * {@code webFilterChain} — sans cette desactivation, il s'executerait
     * jusqu'a trois fois par requete (une fois par auto-enregistrement,
     * une fois par chaine explicite).
     */
    @Bean
    public FilterRegistrationBean<RestoreMaintenanceFilter> restoreMaintenanceFilterRegistration(
            RestoreMaintenanceFilter restoreMaintenanceFilter) {
        FilterRegistrationBean<RestoreMaintenanceFilter> registration =
                new FilterRegistrationBean<>(restoreMaintenanceFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Remplace l'auto-configuration Spring Boot de {@code ForwardedHeaderFilter}
     * (correctif securite MOYENNE, audit externe 04/08/2026) — voir la
     * javadoc de {@link TrustAwareForwardedHeaderFilter} pour le detail du
     * probleme (getRemoteAddr() reecrit sans verification si
     * {@code server.forward-headers-strategy=framework} est positionne) et
     * du correctif. {@code Ordered.HIGHEST_PRECEDENCE} : meme rang que
     * l'auto-configuration Spring Boot remplacee, pour s'executer avant la
     * chaine de securite (FilterChainProxy) et tout autre filtre
     * applicatif — {@code ClientIpResolver} et le reste de l'application ne
     * doivent jamais voir {@code getRemoteAddr()} deja reecrit par une
     * source non verifiee.
     *
     * <p>Cette classe n'est PAS un {@code @Component} (contrairement a
     * {@code ApiRateLimitingFilter}/{@code RestoreMaintenanceFilter}
     * ci-dessus) : construite directement ici, elle evite tout risque de
     * double auto-enregistrement Spring Boot a desactiver.</p>
     */
    @Bean
    public FilterRegistrationBean<TrustAwareForwardedHeaderFilter> trustAwareForwardedHeaderFilterRegistration(
            ClientIpResolver clientIpResolver) {
        FilterRegistrationBean<TrustAwareForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new TrustAwareForwardedHeaderFilter(clientIpResolver));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Registre explicite des sessions Web (correctif securite MOYENNE, audit
     * 04/08/2026) : {@code maximumSessions(5)} en creait deja un en interne,
     * mais sans reference exploitable ailleurs. Exposer ce bean permet a
     * {@code dev.subnetory.service.SessionInvalidationService} de drainer
     * toutes les sessions existantes apres une restauration reussie —
     * autrement, une session Web deja ouverte conserverait en memoire les
     * autorites/roles charges avant la restauration jusqu'a son expiration
     * naturelle (jusqu'a 30 minutes, voir {@code server.servlet.session.timeout}).
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Necessaire pour que {@link #sessionRegistry()} soit tenu a jour quand
     * une session HTTP expire ou est invalidee cote conteneur (sans ce
     * publisher, seules les nouvelles connexions sont enregistrees, jamais
     * les destructions — le registre grossirait indefiniment avec des
     * sessions mortes).
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    // -------------------------------------------------------
    // Chaine 2 - OpenAPI / Swagger UI
    // -------------------------------------------------------

    @Bean
    @Order(2)
    public SecurityFilterChain openApiFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**"
                )
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .headers(headers -> {
                    headers.frameOptions(frame -> frame.deny());
                    headers.contentTypeOptions(Customizer.withDefaults());
                    headers.referrerPolicy(referrer -> referrer
                            .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    headers.contentSecurityPolicy(csp -> csp
                            .policyDirectives(
                                    "default-src 'self'; " +
                                    "script-src 'self' 'unsafe-inline'; " +
                                    "style-src 'self' 'unsafe-inline'; " +
                                    "img-src 'self' data:; " +
                                    "font-src 'self'; " +
                                    "connect-src 'self'; " +
                                    "worker-src blob:; " +
                                    "object-src 'none'; " +
                                    "frame-ancestors 'none'"));
                })
                .build();
    }

    // -------------------------------------------------------
    // Chaine 3 - Web Thymeleaf
    // -------------------------------------------------------

    @Bean
    @Order(3)
    public SecurityFilterChain webFilterChain(HttpSecurity http,
                                              AuthenticationManager authenticationManager,
                                              RateLimitingAuthenticationFailureHandler failureHandler,
                                              RateLimitingAuthenticationSuccessHandler successHandler,
                                              LoginRateLimitingFilter loginRateLimitingFilter,
                                              RestoreMaintenanceFilter restoreMaintenanceFilter,
                                              SessionRegistry sessionRegistry,
                                              ObjectProvider<MandatoryPasswordChangeService>
                                                      mandatoryPasswordChangeServiceProvider,
                                              ObjectProvider<MfaLoginChallengeService>
                                                      mfaLoginChallengeServiceProvider) throws Exception {
        return http
                .authenticationManager(authenticationManager)
                .sessionManagement(s -> s
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .invalidSessionUrl("/login?expired")
                        .maximumSessions(5)
                        // Bean explicite (correctif securite MOYENNE, audit 04/08/2026) :
                        // sans cette reference, le SessionRegistry interne de
                        // maximumSessions() n'est pas exploitable ailleurs dans
                        // l'application. Necessaire pour drainer les sessions Web
                        // existantes apres une restauration (voir
                        // SessionInvalidationService et le bean sessionRegistry()
                        // ci-dessous) — sans quoi une session dejà ouverte
                        // conserverait en memoire les autorites/roles d'avant la
                        // restauration jusqu'a son expiration naturelle.
                        .sessionRegistry(sessionRegistry)
                        // Correctif UX (04/08/2026, constate en test manuel apres
                        // restauration) : sans expiredUrl, une session marquee
                        // expiree par le SessionRegistry (ConcurrentSessionFilter,
                        // y compris via SessionInvalidationService#expireAllSessions
                        // apres une restauration) affiche le texte brut par defaut
                        // de Spring Security ("This session has been expired...",
                        // non traduit, sans navigation) au lieu de rediriger vers
                        // une page normale. Reutilise la meme cible que
                        // invalidSessionUrl ci-dessus : les deux cas se resolvent de
                        // la meme facon pour l'utilisateur (se reconnecter).
                        .expiredUrl("/login?expired")
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/assets/**", "/error", "/login").permitAll()
                        // ROLE_BACKUP (audit 01/08/2026, cf. DB_PASSWORD_ROTATION_FEASIBILITY.md) :
                        // acces limite aux sauvegardes. Regle plus specifique que
                        // /admin/** ci-dessous, evaluee en premier par Spring Security
                        // (premiere correspondance gagnante) - sans elle, le
                        // @PreAuthorize("hasAnyRole('ADMIN', 'BACKUP')") de
                        // AdminBackupWebController ne serait jamais atteint, la regle
                        // /admin/** -> ADMIN bloquant la requete avant le controleur.
                        .requestMatchers("/admin/backup/**").hasAnyRole("ADMIN", "BACKUP")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .permitAll()
                )
                .headers(configureSecurityHeaders())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendRedirect(request.getContextPath() + "/login"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN))
                )
                .addFilterBefore(rejectUnsafeWebRequestWithoutCsrfToken(), CsrfFilter.class)
                // Mode maintenance restauration (correctif securite MOYENNE,
                // audit 04/08/2026) : voir RestoreMaintenanceGate / le meme
                // filtre est cable dans apiFilterChain ci-dessus.
                .addFilterBefore(restoreMaintenanceFilter, CsrfFilter.class)
                .addFilterBefore(loginRateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new MandatoryPasswordChangeFilter(
                                mandatoryPasswordChangeServiceProvider.getIfAvailable()),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new MfaChallengeFilter(
                                mfaLoginChallengeServiceProvider.getIfAvailable()),
                        MandatoryPasswordChangeFilter.class)
                .build();
    }

    /**
     * Meme raison que {@code apiRateLimitingFilterRegistration} ci-dessus :
     * {@code LoginRateLimitingFilter} est un {@code @Component} (bean de type
     * {@code Filter}), donc auto-enregistre par Spring Boot sur {@code /*}
     * en plus de son rattachement explicite a {@code webFilterChain}.
     *
     * <p>Impact fonctionnel de ce doublon avant correction : nul.
     * {@code LoginRateLimitingFilter} ne fait qu'une lecture pure
     * ({@code isLocked}) sans effet de bord ni compteur incremente ; les
     * echecs de connexion sont comptabilises ailleurs, par
     * {@code RateLimitingAuthenticationFailureHandler}, invoque une seule
     * fois par Spring Security independamment de ce filtre. Corrige tout de
     * meme par hygiene et coherence avec le correctif applique sur
     * {@code ApiRateLimitingFilter} (Sprint 2.36 / F7) : le filtre ne doit
     * s'executer qu'une fois, sur le perimetre voulu, jamais sur
     * l'integralite des requetes.</p>
     */
    @Bean
    public FilterRegistrationBean<LoginRateLimitingFilter> loginRateLimitingFilterRegistration(
            LoginRateLimitingFilter loginRateLimitingFilter) {
        FilterRegistrationBean<LoginRateLimitingFilter> registration =
                new FilterRegistrationBean<>(loginRateLimitingFilter);
        registration.setEnabled(false);
        return registration;
    }

    // -------------------------------------------------------
    // Filtre CSRF explicite
    // -------------------------------------------------------

    private OncePerRequestFilter rejectUnsafeWebRequestWithoutCsrfToken() {
        return new OncePerRequestFilter() {
            private static final Set<String> SAFE_METHODS =
                    Set.of("GET", "HEAD", "TRACE", "OPTIONS");

            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                             HttpServletResponse response,
                                             FilterChain filterChain)
                    throws ServletException, IOException {
                if (requiresCsrfToken(request) && !hasSubmittedCsrfToken(request)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }
                filterChain.doFilter(request, response);
            }

            private boolean requiresCsrfToken(HttpServletRequest request) {
                return !SAFE_METHODS.contains(request.getMethod().toUpperCase());
            }

            private boolean hasSubmittedCsrfToken(HttpServletRequest request) {
                return StringUtils.hasText(request.getParameter("_csrf"))
                    || StringUtils.hasText(request.getHeader("X-CSRF-TOKEN"));
            }
        };
    }

    // -------------------------------------------------------
    // Headers securite
    // -------------------------------------------------------

    private Customizer<HeadersConfigurer<HttpSecurity>> configureSecurityHeaders() {
        return headers -> {
            headers.frameOptions(frame -> frame.deny());

            headers.xssProtection(xss -> xss
                    .headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK));

            headers.contentTypeOptions(Customizer.withDefaults());

            headers.referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));

            // Security 7 : permissionsPolicy() est deprecated for removal au profit de
            // permissionsPolicyHeader(), meme configuration (PermissionsPolicyConfig).
            headers.permissionsPolicyHeader(permissions -> permissions
                    .policy("camera=(), microphone=(), geolocation=(), payment=(), " +
                            "usb=(), magnetometer=(), gyroscope=()"));

            headers.contentSecurityPolicy(csp -> csp
                    .policyDirectives(
                            "default-src 'self'; " +
                            "script-src 'self'; " +
                            "style-src 'self'; " +
                            "img-src 'self' data:; " +
                            "font-src 'self'; " +
                            "connect-src 'self'; " +
                            "object-src 'none'; " +
                            "frame-ancestors 'none'; " +
                            "base-uri 'self'; " +
                            "form-action 'self'"));
        };
    }

    // -------------------------------------------------------
    // JWT
    // -------------------------------------------------------

    @Bean
    public JwtDecoder jwtDecoder(RevokedTokenValidator revokedTokenValidator,
                                 UserTokenInvalidationValidator userTokenInvalidationValidator) {
        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("subnetory"),
                revokedTokenValidator,
                userTokenInvalidationValidator
        );
        decoder.setJwtValidator(validator);

        return decoder;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter conv = new JwtGrantedAuthoritiesConverter();
        conv.setAuthoritiesClaimName("roles");
        conv.setAuthorityPrefix("");
        JwtAuthenticationConverter jwtConv = new JwtAuthenticationConverter();
        jwtConv.setJwtGrantedAuthoritiesConverter(conv);
        return jwtConv;
    }

    // -------------------------------------------------------
    // Beans communs
    // -------------------------------------------------------

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * ProviderManager de la chaine Web.
     *
     * LOCAL toujours en premier.
     * LDAP en second si active.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            SubnetoryUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            ObjectProvider<DynamicLdapAuthenticationProvider> dynamicLdapAuthenticationProvider) {

        // Security 7 : le constructeur prend le UserDetailsService, l'encodeur se règle via le setter
        // (inversion du sens utilisé en 6.3+, où le PasswordEncoder était passé au constructeur).
        DaoAuthenticationProvider localProvider = new DaoAuthenticationProvider(userDetailsService);
        localProvider.setPasswordEncoder(passwordEncoder);

        List<AuthenticationProvider> providers = new ArrayList<>();
        providers.add(localProvider);

        DynamicLdapAuthenticationProvider ldap = dynamicLdapAuthenticationProvider.getIfAvailable();
        if (ldap != null) {
            providers.add(ldap);
        }

        return new ProviderManager(providers);
    }
}
