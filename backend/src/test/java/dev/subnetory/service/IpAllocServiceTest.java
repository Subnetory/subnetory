package dev.subnetory.service;

import dev.subnetory.domain.Subnet;
import dev.subnetory.domain.NetworkContext;
import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.AddressRepository;
import dev.subnetory.repository.SubnetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests unitaires du contrat applicatif de {@link IpAllocService}. */
@ExtendWith(MockitoExtension.class)
class IpAllocServiceTest {

    @Mock SubnetRepository subnetRepository;
    @Mock AddressRepository addressRepository;
    @Mock ContextAccessService contextAccessService;

    IpAllocService service;

    @BeforeEach
    void setUp() {
        service = new IpAllocService(subnetRepository, addressRepository, contextAccessService);
    }

    @Test
    @DisplayName("Delegue l'allocation SQL et construit la reponse")
    void findAvailableIps_delegatesToNativeRepository() {
        stubSubnet(1L, "10.0.0.0/8");
        when(addressRepository.findAvailableIps(1L, 5))
                .thenReturn(List.of("10.0.0.2", "10.0.0.4"));

        AvailableIpResponse response = service.findAvailableIps(1L, 5);

        assertThat(response.network()).isEqualTo("10.0.0.0/8");
        assertThat(response.requested()).isEqualTo(5);
        assertThat(response.found()).isEqualTo(2);
        assertThat(response.availableIps()).containsExactly("10.0.0.2", "10.0.0.4");
        verify(addressRepository).findAvailableIps(1L, 5);
    }

    @Test
    @DisplayName("Plafonne la demande a 50 resultats")
    void findAvailableIps_capsRequestedCount() {
        stubSubnet(2L, "10.0.0.0/8");
        when(addressRepository.findAvailableIps(2L, IpAllocService.MAX_RESULTS))
                .thenReturn(List.of("10.0.0.1"));

        AvailableIpResponse response = service.findAvailableIps(2L, 500);

        assertThat(response.requested()).isEqualTo(IpAllocService.MAX_RESULTS);
        assertThat(response.found()).isEqualTo(1);
        verify(addressRepository).findAvailableIps(2L, IpAllocService.MAX_RESULTS);
    }

    @Test
    @DisplayName("Subnet absent : conserve ResourceNotFoundException")
    void findAvailableIps_missingSubnet_throwsResourceNotFound() {
        when(subnetRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findAvailableIps(999L, 5))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Subnet")
                .hasMessageContaining("999");
    }

    private void stubSubnet(Long id, String network) {
        Subnet subnet = new Subnet();
        subnet.setId(id);
        subnet.setNetwork(network);
        NetworkContext context = new NetworkContext();
        context.setId(1L);
        subnet.setContext(context);
        when(subnetRepository.findById(id)).thenReturn(Optional.of(subnet));
    }
}
