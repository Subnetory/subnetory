package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.subnetory.domain.Address;
import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.domain.Subnet;
import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.SeriesConflictException;
import dev.subnetory.repository.AddressRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Tests unitaires de {@link SeriesReservationService} (repository et SubnetService simules). */
@ExtendWith(MockitoExtension.class)
class SeriesReservationServiceTest {

    private static final Long SUBNET_ID = 5L;

    @Mock SubnetService subnetService;
    @Mock AddressRepository addressRepository;
    @Captor ArgumentCaptor<List<Address>> savedCaptor;

    SeriesReservationService service;
    Subnet subnet;

    @BeforeEach
    void setUp() {
        service = new SeriesReservationService(subnetService, addressRepository);
        subnet = buildSubnet("10.10.10.0/24", null);
        lenient().when(subnetService.getEntityById(SUBNET_ID)).thenReturn(subnet);
    }

    // -------------------------------------------------------
    // preview
    // -------------------------------------------------------

    @Test
    @DisplayName("preview : signale l'adresse occupee et n'ecrit rien")
    void preview_flagsOccupiedAddressAndWritesNothing() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.23"))
                .thenReturn(List.of("10.10.10.22"));

        SeriesReservationResponse response = service.preview(request("10.10.10.21", 3, null));

        assertThat(response.network()).isEqualTo("10.10.10.0/24");
        assertThat(response.mode()).isEqualTo("block");
        assertThat(response.canCreate()).isFalse();
        assertThat(response.requested()).isEqualTo(3);
        assertThat(response.rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("AVAILABLE", "OCCUPIED", "AVAILABLE");
        assertThat(response.rows()).extracting(SeriesReservationResponse.Entry::hostname)
                .containsExactly("NTNX-HOST-01", "NTNX-HOST-02", "NTNX-HOST-03");
        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("preview : la passerelle (avec ou sans prefixe) est signalee RESERVED")
    void preview_gatewayIsReserved() {
        subnet.setGateway("10.10.10.1/32");
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.1", "10.10.10.2"))
                .thenReturn(List.of());

        SeriesReservationResponse response = service.preview(request("10.10.10.1", 2, null));

        assertThat(response.rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("RESERVED", "AVAILABLE");
        assertThat(response.canCreate()).isFalse();
    }

    @Test
    @DisplayName("preview : depart hors sous-reseau -> OUT_OF_RANGE, sans requete de conflits")
    void preview_startOutsideSubnetSkipsDatabaseLookup() {
        SeriesReservationResponse response = service.preview(request("10.10.11.5", 2, null));

        assertThat(response.rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("OUT_OF_RANGE", "OUT_OF_RANGE");
        assertThat(response.canCreate()).isFalse();
        verify(addressRepository, never()).findOccupiedIpsBetween(any(), any(), any());
    }

    @Test
    @DisplayName("preview : un sous-reseau IPv6 est refuse")
    void preview_ipv6SubnetIsRejected() {
        subnet.setNetwork("2001:db8::/64");

        assertThatThrownBy(() -> service.preview(request("10.10.10.21", 3, null)))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("IPv4");
    }

    // -------------------------------------------------------
    // create
    // -------------------------------------------------------

    @Test
    @DisplayName("create : toute la serie est enregistree en un seul lot")
    void create_savesWholeSeriesInOneBatch() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.23"))
                .thenReturn(List.of());
        SeriesReservationRequest request = new SeriesReservationRequest(
                SUBNET_ID, "10.10.10.21", 3, null, "NTNX-HOST-", null, null, null, null,
                "Nutanix cluster", true);

        SeriesReservationResponse response = service.create(request, "nahim");

        assertThat(response.canCreate()).isTrue();
        verify(addressRepository).saveAllAndFlush(savedCaptor.capture());
        List<Address> saved = savedCaptor.getValue();
        assertThat(saved).extracting(Address::getAddress)
                .containsExactly("10.10.10.21", "10.10.10.22", "10.10.10.23");
        assertThat(saved).extracting(Address::getHostname)
                .containsExactly("NTNX-HOST-01", "NTNX-HOST-02", "NTNX-HOST-03");
        assertThat(saved).allSatisfy(a -> {
            assertThat(a.getSubnet()).isSameAs(subnet);
            assertThat(a.getContext()).isSameAs(subnet.getContext());
            assertThat(a.getSite()).isSameAs(subnet.getSite());
            assertThat(a.getModifiedBy()).isEqualTo("nahim");
            assertThat(a.getDescription()).isEqualTo("Nutanix cluster");
            assertThat(a.isTemporary()).isTrue();
            assertThat(a.getDiscoverySource()).isEqualTo("manual");
        });
    }

    @Test
    @DisplayName("create : une description vide est enregistree comme absente")
    void create_blankDescriptionBecomesNull() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.21"))
                .thenReturn(List.of());
        SeriesReservationRequest request = new SeriesReservationRequest(
                SUBNET_ID, "10.10.10.21", 1, null, "h", null, null, null, null, "   ", null);

        service.create(request, "nahim");

        verify(addressRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).singleElement().satisfies(a -> {
            assertThat(a.getDescription()).isNull();
            assertThat(a.isTemporary()).isFalse();
        });
    }

    @Test
    @DisplayName("create : mode block + adresse occupee -> SeriesConflictException, rien n'est cree")
    void create_blockModeConflictCreatesNothing() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.23"))
                .thenReturn(List.of("10.10.10.22"));

        assertThatThrownBy(() -> service.create(request("10.10.10.21", 3, null), "nahim"))
                .isInstanceOfSatisfying(SeriesConflictException.class, e -> {
                    assertThat(e.getMessage()).contains("1 address(es)").contains("no address was created");
                    assertThat(e.getPreview().canCreate()).isFalse();
                    assertThat(e.getPreview().rows()).extracting(SeriesReservationResponse.Entry::status)
                            .containsExactly("AVAILABLE", "OCCUPIED", "AVAILABLE");
                });

        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("create : mode skip -> adresses occupees sautees, noms consecutifs")
    void create_skipModeSkipsOccupiedAddresses() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.254"))
                .thenReturn(List.of("10.10.10.22"));

        SeriesReservationResponse response = service.create(request("10.10.10.21", 3, "skip"), "nahim");

        assertThat(response.mode()).isEqualTo("skip");
        assertThat(response.skipped()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.22");
        verify(addressRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).extracting(Address::getAddress)
                .containsExactly("10.10.10.21", "10.10.10.23", "10.10.10.24");
        assertThat(savedCaptor.getValue()).extracting(Address::getHostname)
                .containsExactly("NTNX-HOST-01", "NTNX-HOST-02", "NTNX-HOST-03");
    }

    @Test
    @DisplayName("create : mode skip + plage epuisee -> SeriesConflictException, rien n'est cree")
    void create_skipModeRangeExhaustedCreatesNothing() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.253", "10.10.10.254"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(request("10.10.10.253", 4, "skip"), "nahim"))
                .isInstanceOfSatisfying(SeriesConflictException.class,
                        e -> assertThat(e.getMessage()).contains("2 address(es) missing"));

        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("create : violation d'unicite concurrente -> ConflictException (transaction annulee)")
    void create_concurrentInsertIsReportedAsConflict() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.22"))
                .thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("uq_addresses_address_subnet"))
                .when(addressRepository).saveAllAndFlush(any());

        assertThatThrownBy(() -> service.create(request("10.10.10.21", 2, null), "nahim"))
                .isInstanceOf(ConflictException.class)
                .isNotInstanceOf(SeriesConflictException.class)
                .hasMessageContaining("concurrently");
    }

    @Test
    @DisplayName("create : parametres invalides refuses avant tout acces a la base")
    void create_invalidParametersFailBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.create(request("10.10.10.21", 0, null), "nahim"))
                .isInstanceOf(InvalidSeriesException.class);

        verifyNoInteractions(addressRepository);
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private static SeriesReservationRequest request(String startIp, int count, String occupiedMode) {
        return new SeriesReservationRequest(
                SUBNET_ID, startIp, count, null, "NTNX-HOST-", null, null, null, occupiedMode, null, null);
    }

    private static Subnet buildSubnet(String network, String gateway) {
        NetworkContext context = new NetworkContext();
        context.setId(1L);
        Site site = new Site();
        site.setId(2L);

        Subnet subnet = new Subnet();
        subnet.setId(SUBNET_ID);
        subnet.setNetwork(network);
        subnet.setGateway(gateway);
        subnet.setContext(context);
        subnet.setSite(site);
        return subnet;
    }
}
