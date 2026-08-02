package dev.subnetory.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.ui.ExtendedModelMap;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires simples (sans contexte Spring), memes principes que
 * {@link HelpWebControllerTest} : verifie que chaque route du guide
 * administrateur renvoie la bonne vue et les bons attributs de modele.
 * La restriction ROLE_ADMIN elle-meme n'est pas testee ici : elle vient de
 * la regle de securite existante sur /admin/** (SecurityConfig), deja
 * couverte par les tests de securite de ce filtre.
 *
 * <p>MessageSource reel (et non mocke) : {@code messages.properties} etant
 * sur le classpath de test via {@code src/main/resources}, ceci verifie les
 * vraies traductions plutot que de les dupliquer en dur ici, et reste donc
 * automatiquement synchronise si les libelles changent.</p>
 */
class AdminHelpWebControllerTest {

    private static final Locale LOCALE = Locale.FRENCH;

    private final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();

    private final AdminHelpWebController controller;

    AdminHelpWebControllerTest() {
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        controller = new AdminHelpWebController(messageSource);
    }

    @Test
    void hub_setsModelAttributesAndReturnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.hub(model, LOCALE);

        assertThat(view).isEqualTo("admin/help");
        assertThat(model.get("activeSection")).isEqualTo("admin");
        assertThat(model.get("pageTitle")).isEqualTo("Guide administrateur");
    }

    @Test
    void utilisateursRoles_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.utilisateursRoles(model, LOCALE);

        assertThat(view).isEqualTo("admin/help/utilisateurs-roles");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void audit_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.audit(model, LOCALE);

        assertThat(view).isEqualTo("admin/help/audit");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void sauvegardes_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.sauvegardes(model, LOCALE);

        assertThat(view).isEqualTo("admin/help/sauvegardes");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void deploiementDocker_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.deploiementDocker(model, LOCALE);

        assertThat(view).isEqualTo("admin/help/deploiement-docker");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void deploiementKubernetes_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.deploiementKubernetes(model, LOCALE);

        assertThat(view).isEqualTo("admin/help/deploiement-kubernetes");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }
}
