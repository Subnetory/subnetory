package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.repository.NetworkContextRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkContextServiceAccessTest {

    private final NetworkContextRepository contextRepository = mock(NetworkContextRepository.class);
    private final ContextAccessService contextAccessService = mock(ContextAccessService.class);
    private final NetworkContextService contextService =
            new NetworkContextService(contextRepository, contextAccessService);

    @Test
    void findAllReturnsOnlyAllowedContexts() {
        var pageable = PageRequest.of(0, 20);
        NetworkContext production = context(2L, "Production");
        when(contextAccessService.allowedContextIds()).thenReturn(List.of(2L));
        when(contextRepository.findByIdIn(List.of(2L), pageable))
                .thenReturn(new PageImpl<>(List.of(production), pageable, 1));

        var result = contextService.findAll(pageable);

        assertThat(result.getContent())
                .extracting("id", "name")
                .containsExactly(tuple(2L, "Production"));
        verify(contextRepository, never()).findAll();
    }

    @Test
    void findAllAccessibleReturnsEmptyListWhenNoContextIsAllowed() {
        when(contextAccessService.allowedContextIds()).thenReturn(List.of());

        assertThat(contextService.findAllAccessible()).isEmpty();

        verify(contextRepository, never()).findByIdInOrderByNameAsc(any());
    }

    @Test
    void findByIdRequiresContextAccessBeforeLoadingEntity() {
        NetworkContext production = context(7L, "Production");
        when(contextRepository.findById(7L)).thenReturn(Optional.of(production));

        var result = contextService.findById(7L);

        verify(contextAccessService).requireAccess(7L);
        assertThat(result.id()).isEqualTo(7L);
    }

    private NetworkContext context(Long id, String name) {
        NetworkContext context = new NetworkContext();
        context.setId(id);
        context.setName(name);
        context.setDescription(name);
        return context;
    }
}
