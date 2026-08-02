package dev.subnetory.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Correctif MOYENNE (02/08/2026) : le lien de bascule de langue de
 * layout/base.html (@{${currentRequestPath}(lang='fr')}) doit conserver les
 * filtres/la pagination actifs au lieu de toujours revenir a la page 1 sans
 * filtre. Voir NavigationModelAdvice.currentRequestPathWithQuery().
 */
class NavigationModelAdviceTest {

    private final NavigationModelAdvice advice =
            new NavigationModelAdvice(null, null);

    @Test
    void noQueryString_returnsPathOnly() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/network/addresses");
        when(request.getQueryString()).thenReturn(null);

        assertThat(advice.currentRequestPathWithQuery(request)).isEqualTo("/network/addresses");
    }

    @Test
    void queryStringWithoutLang_isPreserved() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/network/addresses");
        when(request.getQueryString()).thenReturn("subnetId=3&page=2");

        assertThat(advice.currentRequestPathWithQuery(request))
                .isEqualTo("/network/addresses?subnetId=3&page=2");
    }

    @Test
    void existingLangParam_isStrippedToAvoidDuplication() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/admin/audit-log");
        when(request.getQueryString()).thenReturn("eventType=LOGIN_FAILURE&lang=en&page=1");

        assertThat(advice.currentRequestPathWithQuery(request))
                .isEqualTo("/admin/audit-log?eventType=LOGIN_FAILURE&page=1");
    }

    @Test
    void queryStringOnlyLang_fallsBackToPathOnly() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/help");
        when(request.getQueryString()).thenReturn("lang=fr");

        assertThat(advice.currentRequestPathWithQuery(request)).isEqualTo("/help");
    }

    @Test
    void unsafeRequestUri_fallsBackToRoot() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("//evil.example.com");
        when(request.getQueryString()).thenReturn(null);

        assertThat(advice.currentRequestPathWithQuery(request)).isEqualTo("/");
    }
}
