package dev.subnetory.config;

import java.time.Duration;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Internationalisation (01/08/2026) : francais (langue par defaut de
 * l'application depuis sa creation) et anglais.
 *
 * <p>La langue active est memorisee dans un cookie non sensible
 * ({@value #LANG_COOKIE_NAME}), pas en session ni en base : un changement de
 * langue ne necessite donc aucune migration Flyway et survit a un
 * redemarrage du navigateur, conformement a la philosophie "deploiement
 * simple, pas de dependance inutile" du projet.
 *
 * <p>Bascule via le parametre de requete {@code ?lang=fr} ou {@code ?lang=en}
 * (voir le selecteur dans {@code layout/base.html}), interceptee par
 * {@link LocaleChangeInterceptor} avant que la vue ne soit rendue.
 *
 * <p>Deploiement progressif des traductions (voir {@code messages_fr.properties}
 * / {@code messages_en.properties}) : navigation, tableau de bord et CRUD
 * reseau (contextes/sites/VLANs/sous-reseaux/adresses) en premier ;
 * administration, aide et messages de validation/erreur dans une prochaine
 * passe. Les cles absentes retombent sur le francais via
 * {@code fallback-to-system-locale: false} + bundle par defaut
 * {@code messages.properties} identique a {@code messages_fr.properties}.
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    static final String LANG_COOKIE_NAME = "subnetory.lang";

    /** Un an : une preference de langue n'a pas de raison d'expirer plus vite. */
    private static final Duration LANG_COOKIE_MAX_AGE = Duration.ofDays(365);

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LANG_COOKIE_NAME);
        resolver.setDefaultLocale(Locale.FRENCH);
        resolver.setCookieMaxAge(LANG_COOKIE_MAX_AGE);
        resolver.setCookiePath("/");
        // Pas de donnee sensible dans ce cookie : lisible cote client n'est pas un risque,
        // mais il n'a pas non plus besoin d'etre modifiable en JS -> httpOnly par defaut.
        resolver.setCookieHttpOnly(true);
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }
}
