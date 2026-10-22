package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.csv.AddressCsvParser;
import dev.subnetory.csv.AddressXlsxParser;
import dev.subnetory.domain.Address;
import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.AddressResponse;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.AddressRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressServiceDeleteAllTest {

    @Mock AddressRepository addressRepository;
    @Mock SubnetService subnetService;
    @Mock AddressCsvParser csvParser;
    @Mock AddressXlsxParser xlsxParser;
    @Mock ContextAccessService contextAccessService;

    AddressService service;

    @BeforeEach
    void setUp() {
        service = new AddressService(
                addressRepository, subnetService, csvParser, xlsxParser, contextAccessService);
    }

    @Test
    void deleteAll_deletesAccessibleAddressesAndReturnsThemForAudit() {
        Address first = address(1L, "10.0.0.1/24", 5L);
        Address second = address(2L, "10.0.0.2/24", 5L);
        when(addressRepository.findById(1L)).thenReturn(Optional.of(first));
        when(addressRepository.findById(2L)).thenReturn(Optional.of(second));

        List<AddressResponse> deleted = service.deleteAll(List.of(1L, 2L));

        assertThat(deleted).extracting(AddressResponse::id).containsExactly(1L, 2L);
        assertThat(deleted).extracting(AddressResponse::address)
                .containsExactly("10.0.0.1", "10.0.0.2");
        verify(addressRepository).delete(first);
        verify(addressRepository).delete(second);
    }

    @Test
    void deleteAll_ignoresDuplicateIds() {
        Address first = address(1L, "10.0.0.1/24", 5L);
        when(addressRepository.findById(1L)).thenReturn(Optional.of(first));

        List<AddressResponse> deleted = service.deleteAll(List.of(1L, 1L, 1L));

        assertThat(deleted).hasSize(1);
        verify(addressRepository).delete(first);
    }

    @Test
    void deleteAll_skipsUnknownAndInaccessibleAddresses() {
        Address visible = address(1L, "10.0.0.1/24", 5L);
        Address hidden = address(2L, "10.0.1.1/24", 9L);
        when(addressRepository.findById(1L)).thenReturn(Optional.of(visible));
        when(addressRepository.findById(2L)).thenReturn(Optional.of(hidden));
        when(addressRepository.findById(404L)).thenReturn(Optional.empty());
        doThrow(new ResourceNotFoundException("Address", 2L))
                .when(contextAccessService).requireResourceAccess(9L, "Address", 2L);

        List<AddressResponse> deleted = service.deleteAll(List.of(404L, 2L, 1L));

        assertThat(deleted).extracting(AddressResponse::id).containsExactly(1L);
        verify(addressRepository).delete(visible);
        verify(addressRepository, never()).delete(hidden);
    }

    @Test
    void deleteAll_withNoId_deletesNothing() {
        assertThat(service.deleteAll(List.of())).isEmpty();
        verify(addressRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    private static Address address(Long id, String inet, Long contextId) {
        NetworkContext context = new NetworkContext();
        context.setId(contextId);
        context.setName("ctx-" + contextId);
        Site site = new Site();
        site.setId(1L);
        site.setName("site");
        Subnet subnet = new Subnet();
        subnet.setId(7L);
        subnet.setNetwork("10.0.0.0/24");

        Address address = new Address();
        address.setId(id);
        address.setAddress(inet);
        address.setContext(context);
        address.setSite(site);
        address.setSubnet(subnet);
        return address;
    }
}
