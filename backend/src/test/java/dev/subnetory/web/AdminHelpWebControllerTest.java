package dev.subnetory.web;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires simples (sans contexte Spring), memes principes que
 * {@link HelpWebControllerTest} : verifie que chaque route du guide
 * administrateur renvoie la bonne vue et les bons attributs de modele.
 * La restriction ROLE_ADMIN elle-meme n'est pas testee ici : elle vient de
 * la regle de securite existante sur /admin/** (SecurityConfig), deja
 * couverte par les tests de securite de ce filtre.
 */
class AdminHelpWebControllerTest {

    private final AdminHelpWebController controller = new AdminHelpWebController();

    @Test
    void hub_setsModelAttributesAndReturnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.hub(model);

        assertThat(view).isEqualTo("admin/help");
        assertThat(model.get("activeSection")).isEqualTo("admin");
        assertThat(model.get("pageTitle")).isEqualTo("Guide administrateur");
    }

    @Test
    void utilisateursRoles_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.utilisateursRoles(model);

        assertThat(view).isEqualTo("admin/help/utilisateurs-roles");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void audit_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.audit(model);

        assertThat(view).isEqualTo("admin/help/audit");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void sauvegardes_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.sauvegardes(model);

        assertThat(view).isEqualTo("admin/help/sauvegardes");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void deploiementDocker_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.deploiementDocker(model);

        assertThat(view).isEqualTo("admin/help/deploiement-docker");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }

    @Test
    void deploiementKubernetes_returnsView() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.deploiementKubernetes(model);

        assertThat(view).isEqualTo("admin/help/deploiement-kubernetes");
        assertThat(model.get("activeSection")).isEqualTo("admin");
    }
}
