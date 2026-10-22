package dev.subnetory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

class BulkDeleteTest {

    private final MessageSource messages = mock(MessageSource.class);
    private final RedirectAttributes flash = mock(RedirectAttributes.class);
    private final Locale locale = Locale.FRENCH;

    @Test
    void select_emptyOrNull_flashesErrorAndReturnsNull() {
        when(messages.getMessage("flash.bulkDelete.none", null, locale)).thenReturn("aucun");

        assertThat(BulkDelete.select(null, flash, messages, locale)).isNull();
        assertThat(BulkDelete.select(List.of(), flash, messages, locale)).isNull();

        verify(flash, org.mockito.Mockito.times(2)).addFlashAttribute("flashError", "aucun");
    }

    @Test
    void select_removesDuplicatesAndKeepsOrder() {
        Set<Long> selected = BulkDelete.select(List.of(3L, 1L, 3L, 2L), flash, messages, locale);

        assertThat(selected).containsExactly(3L, 1L, 2L);
        verify(flash, never()).addFlashAttribute(any(String.class), any());
    }

    @Test
    void select_atLimit_isAccepted() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= BulkDelete.MAX; i++) ids.add(i);

        assertThat(BulkDelete.select(ids, flash, messages, locale)).hasSize(BulkDelete.MAX);
    }

    @Test
    void select_overLimit_flashesErrorAndReturnsNull() {
        when(messages.getMessage(eq("flash.bulkDelete.tooMany"), any(Object[].class), eq(locale)))
                .thenReturn("trop");
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= BulkDelete.MAX + 1; i++) ids.add(i);

        assertThat(BulkDelete.select(ids, flash, messages, locale)).isNull();

        verify(flash).addFlashAttribute("flashError", "trop");
    }

    @Test
    void flashResult_allDeleted_flashesSuccess() {
        when(messages.getMessage(eq("flash.bulkDelete.success"), any(Object[].class), eq(locale)))
                .thenReturn("ok");

        BulkDelete.flashResult(3, 0, flash, messages, locale);

        verify(flash).addFlashAttribute("flashSuccess", "ok");
        verify(flash, never()).addFlashAttribute(eq("flashError"), any());
    }

    @Test
    void flashResult_someFailed_flashesError() {
        when(messages.getMessage(eq("flash.bulkDelete.partial"), any(Object[].class), eq(locale)))
                .thenReturn("partiel");

        BulkDelete.flashResult(2, 1, flash, messages, locale);

        verify(flash).addFlashAttribute("flashError", "partiel");
        verify(flash, never()).addFlashAttribute(eq("flashSuccess"), any());
    }

    @Test
    void hasAnyRole_matchesGrantedAuthority() {
        var auth = new TestingAuthenticationToken("u", "p", "ROLE_IP");

        assertThat(BulkDelete.hasAnyRole(auth, "ROLE_ADMIN", "ROLE_IP")).isTrue();
        assertThat(BulkDelete.hasAnyRole(auth, "ROLE_ADMIN")).isFalse();
        assertThat(BulkDelete.hasAnyRole(null, "ROLE_ADMIN")).isFalse();
    }
}
