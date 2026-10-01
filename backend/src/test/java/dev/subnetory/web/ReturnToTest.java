package dev.subnetory.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReturnToTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/network/sites?page=1&q=dem+o",
            "/network/vlans?page=2&siteId=3&q=%C3%A9t%C3%A9",
            "/network/subnets",
            "/search?q=10.1.4"})
    @DisplayName("Chemins internes acceptés")
    void internalPathsAccepted(String value) {
        assertThat(ReturnTo.sanitize(value)).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example/",
            "//evil.example/network/x",
            "/network/../admin/users",
            "/admin/users",
            "/network/x\\y",
            "/network/{x}",
            "javascript:alert(1)",
            "/networkx/a",
            "/network//x"})
    @DisplayName("URL externes, chemins hors périmètre et caractères dangereux refusés")
    void unsafeValuesRejected(String value) {
        assertThat(ReturnTo.sanitize(value)).isNull();
    }

    @Test
    @DisplayName("Valeurs vides, contrôle, trop longues : refusées")
    void emptyControlAndTooLongRejected() {
        assertThat(ReturnTo.sanitize(null)).isNull();
        assertThat(ReturnTo.sanitize("  ")).isNull();
        assertThat(ReturnTo.sanitize("/network/x\r\nSet-Cookie: a=b")).isNull();
        assertThat(ReturnTo.sanitize("/network/" + "a".repeat(600))).isNull();
    }

    @Test
    @DisplayName("Hors requête Web : repli sur l'URL par défaut")
    void withoutRequest_usesFallback() {
        assertThat(ReturnTo.redirect("/network/vlans")).isEqualTo("redirect:/network/vlans");
        assertThat(ReturnTo.withReturnTo("/network/vlans/5")).isEqualTo("/network/vlans/5");
        assertThat(ReturnTo.cancelUrl("/network/vlans")).isEqualTo("/network/vlans");
    }
}
