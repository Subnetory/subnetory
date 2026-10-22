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
import dev.subnetory.dto.SeriesGroupsRequest;
import dev.subnetory.dto.SeriesGroupsResponse;
import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.dto.SeriesRowsRequest;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.InvalidSeriesException;
import dev.subnetory.exception.SeriesConflictException;
import dev.subnetory.exception.SeriesGroupsConflictException;
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
    // series enchainees (groupes)
    // -------------------------------------------------------

    @Test
    @DisplayName("previewGroups : les groupes s'enchainent a la suite l'un de l'autre")
    void previewGroups_chainsGroupsOneAfterTheOther() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.23"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.24", "10.10.10.25"))
                .thenReturn(List.of("10.10.10.25"));

        SeriesGroupsResponse response = service.previewGroups(groups("10.10.10.21", 0, null,
                group("HOST", 3, "H-"), group("CVM", 2, "C-")));

        assertThat(response.network()).isEqualTo("10.10.10.0/24");
        assertThat(response.mode()).isEqualTo("block");
        assertThat(response.requested()).isEqualTo(5);
        assertThat(response.canCreate()).isFalse();
        assertThat(response.groups()).extracting(SeriesGroupsResponse.Group::label)
                .containsExactly("HOST", "CVM");
        assertThat(response.groups().get(0).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.21", "10.10.10.22", "10.10.10.23");
        assertThat(response.groups().get(0).rows()).extracting(SeriesReservationResponse.Entry::hostname)
                .containsExactly("H-01", "H-02", "H-03");
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.24", "10.10.10.25");
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("AVAILABLE", "OCCUPIED");
        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("previewGroups : l'ecart laisse des adresses libres entre deux groupes")
    void previewGroups_gapLeavesFreeAddressesBetweenGroups() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.22"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.25", "10.10.10.26"))
                .thenReturn(List.of());

        SeriesGroupsResponse response = service.previewGroups(groups("10.10.10.21", 2, null,
                group("A", 2, "A-"), group("B", 2, "B-")));

        assertThat(response.canCreate()).isTrue();
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.25", "10.10.10.26");
    }

    @Test
    @DisplayName("previewGroups : mode skip, chaque groupe repart apres la derniere adresse retenue")
    void previewGroups_skipModeChainsAfterLastRetainedAddress() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.254"))
                .thenReturn(List.of("10.10.10.22"));
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.25", "10.10.10.254"))
                .thenReturn(List.of());

        SeriesGroupsResponse response = service.previewGroups(groups("10.10.10.21", 0, "skip",
                group("HOST", 3, "H-"), group("CVM", 2, "C-")));

        assertThat(response.mode()).isEqualTo("skip");
        assertThat(response.canCreate()).isTrue();
        assertThat(response.groups().get(0).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.21", "10.10.10.23", "10.10.10.24");
        assertThat(response.groups().get(0).skipped()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.22");
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.25", "10.10.10.26");
    }

    @Test
    @DisplayName("previewGroups : l'IP de depart propre a un groupe remplace l'enchainement")
    void previewGroups_groupStartIpOverridesChaining() {
        SeriesGroupsResponse response = service.previewGroups(groups("10.10.10.21", 0, null,
                group("A", 2, "A-"),
                new SeriesGroupsRequest.Group("B", 2, null, "B-", null, null, null, "10.10.10.50"),
                group("C", 1, "C-")));

        assertThat(response.canCreate()).isTrue();
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.50", "10.10.10.51");
        // le groupe suivant repart apres la fin du groupe qui avait sa propre IP de depart
        assertThat(response.groups().get(2).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.52");
    }

    @Test
    @DisplayName("previewGroups : une adresse deja prise par un groupe precedent est DUPLICATE")
    void previewGroups_overlapBetweenGroupsIsFlaggedDuplicate() {
        SeriesGroupsResponse response = service.previewGroups(groups("10.10.10.21", 0, null,
                group("A", 3, "A-"),
                new SeriesGroupsRequest.Group("B", 2, null, "B-", null, null, null, "10.10.10.22")));

        assertThat(response.canCreate()).isFalse();
        assertThat(response.groups().get(0).rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsOnly("AVAILABLE");
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("DUPLICATE", "DUPLICATE");
    }

    @Test
    @DisplayName("createGroups : groupes qui se chevauchent -> conflit, rien n'est cree")
    void createGroups_overlapCreatesNothing() {
        assertThatThrownBy(() -> service.createGroups(groups("10.10.10.21", 0, null,
                group("A", 3, "A-"),
                new SeriesGroupsRequest.Group("B", 2, null, "B-", null, null, null, "10.10.10.22")),
                "nahim"))
                .isInstanceOfSatisfying(SeriesGroupsConflictException.class, e -> {
                    assertThat(e.getMessage()).contains("2 address(es)").contains("duplicated");
                    assertThat(e.getPreview().canCreate()).isFalse();
                });

        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("previewGroups : parametres invalides refuses avant tout acces a la base")
    void previewGroups_invalidRequestsAreRejected() {
        assertThatThrownBy(() -> service.previewGroups(groups("10.10.10.21", 0, null)))
                .isInstanceOf(InvalidSeriesException.class);

        SeriesGroupsRequest.Group[] tooMany = new SeriesGroupsRequest.Group[11];
        java.util.Arrays.fill(tooMany, group("G", 1, "G-"));
        assertThatThrownBy(() -> service.previewGroups(groups("10.10.10.21", 0, null, tooMany)))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("10 groups");

        assertThatThrownBy(() -> service.previewGroups(groups("10.10.10.21", 0, null,
                group("A", 200, "A-"), group("B", 100, "B-"))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("256");

        assertThatThrownBy(() -> service.previewGroups(groups("10.10.10.21", 300, null, group("A", 1, "A-"))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("gap");

        assertThatThrownBy(() -> service.previewGroups(groups("10.10.10.21", 0, null,
                new SeriesGroupsRequest.Group("A", null, null, null, null, null, null))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("count");

        assertThatThrownBy(() -> service.previewGroups(groups("10.10.10.21", 0, null,
                (SeriesGroupsRequest.Group) null)))
                .isInstanceOf(InvalidSeriesException.class);

        assertThatThrownBy(() -> service.previewGroups(groups("not-an-ip", 0, null, group("A", 1, "A-"))))
                .isInstanceOf(InvalidSeriesException.class);

        verifyNoInteractions(addressRepository);
    }

    @Test
    @DisplayName("previewGroups : des groupes qui depassent l'espace IPv4 sont refuses")
    void previewGroups_chainBeyondIpv4SpaceIsRejected() {
        assertThatThrownBy(() -> service.previewGroups(groups("255.255.255.250", 0, null,
                group("A", 6, "A-"), group("B", 1, "B-"))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("IPv4");
    }

    @Test
    @DisplayName("createGroups : tous les groupes sont enregistres en un seul lot")
    void createGroups_savesAllGroupsInOneBatch() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.22"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.23", "10.10.10.24"))
                .thenReturn(List.of());
        SeriesGroupsRequest request = new SeriesGroupsRequest(SUBNET_ID, "10.10.10.21", null, null,
                "Nutanix cluster", true, List.of(group("HOST", 2, "H-"), group("CVM", 2, "C-")));

        SeriesGroupsResponse response = service.createGroups(request, "nahim");

        assertThat(response.canCreate()).isTrue();
        verify(addressRepository).saveAllAndFlush(savedCaptor.capture());
        List<Address> saved = savedCaptor.getValue();
        assertThat(saved).extracting(Address::getAddress)
                .containsExactly("10.10.10.21", "10.10.10.22", "10.10.10.23", "10.10.10.24");
        assertThat(saved).extracting(Address::getHostname)
                .containsExactly("H-01", "H-02", "C-01", "C-02");
        assertThat(saved).allSatisfy(a -> {
            assertThat(a.getSubnet()).isSameAs(subnet);
            assertThat(a.getModifiedBy()).isEqualTo("nahim");
            assertThat(a.getDescription()).isEqualTo("Nutanix cluster");
            assertThat(a.isTemporary()).isTrue();
            assertThat(a.getDiscoverySource()).isEqualTo("manual");
        });
    }

    @Test
    @DisplayName("createGroups : un groupe en conflit -> SeriesGroupsConflictException, rien n'est cree")
    void createGroups_conflictCreatesNothing() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.22"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.23", "10.10.10.24"))
                .thenReturn(List.of("10.10.10.24"));

        assertThatThrownBy(() -> service.createGroups(groups("10.10.10.21", 0, null,
                group("HOST", 2, "H-"), group("CVM", 2, "C-")), "nahim"))
                .isInstanceOfSatisfying(SeriesGroupsConflictException.class, e -> {
                    assertThat(e.getMessage()).contains("1 address(es)").contains("no address was created");
                    assertThat(e.getPreview().canCreate()).isFalse();
                    assertThat(e.getPreview().groups().get(1).rows())
                            .extracting(SeriesReservationResponse.Entry::status)
                            .containsExactly("AVAILABLE", "OCCUPIED");
                });

        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("createGroups : mode skip + plage epuisee -> conflit avec le nombre d'adresses manquantes")
    void createGroups_skipModeRangeExhausted() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.253", "10.10.10.254"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.createGroups(groups("10.10.10.253", 0, "skip",
                group("A", 4, "A-")), "nahim"))
                .isInstanceOfSatisfying(SeriesGroupsConflictException.class,
                        e -> assertThat(e.getMessage()).contains("2 address(es) missing"));

        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("createGroups : violation d'unicite concurrente -> ConflictException (transaction annulee)")
    void createGroups_concurrentInsertIsReportedAsConflict() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.21"))
                .thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("uq_addresses_address_subnet"))
                .when(addressRepository).saveAllAndFlush(any());

        assertThatThrownBy(() -> service.createGroups(groups("10.10.10.21", 0, null, group("A", 1, "A-")), "nahim"))
                .isInstanceOf(ConflictException.class)
                .isNotInstanceOf(SeriesGroupsConflictException.class)
                .hasMessageContaining("concurrently");
    }

    // -------------------------------------------------------
    // lignes explicites
    // -------------------------------------------------------

    @Test
    @DisplayName("previewRows : chaque ligne recoit son statut (libre, occupee, passerelle, hors plage, invalide, doublon)")
    void previewRows_classifiesEveryRow() {
        subnet.setGateway("10.10.10.1");
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.1", "10.10.10.30"))
                .thenReturn(List.of("10.10.10.22"));

        SeriesReservationResponse response = service.previewRows(rows(
                row("10.10.10.21", "a"), row("10.10.10.22", "b"), row("10.10.10.1", "c"),
                row("10.10.11.5", "d"), row("not-an-ip", "e"), row("10.10.10.21", "f"),
                row(" 010.010.010.030 ", "g"), row("", "h")));

        assertThat(response.mode()).isEqualTo("rows");
        assertThat(response.canCreate()).isFalse();
        assertThat(response.requested()).isEqualTo(8);
        assertThat(response.rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("AVAILABLE", "OCCUPIED", "RESERVED", "OUT_OF_RANGE",
                        "INVALID", "DUPLICATE", "AVAILABLE", "INVALID");
        assertThat(response.rows().get(6).ip()).isEqualTo("10.10.10.30");
        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("previewRows : toutes les lignes libres -> canCreate")
    void previewRows_allAvailable() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.40"))
                .thenReturn(List.of());

        SeriesReservationResponse response = service.previewRows(
                rows(row("10.10.10.40", "a"), row("10.10.10.21", "b")));

        assertThat(response.canCreate()).isTrue();
    }

    @Test
    @DisplayName("previewRows : aucune adresse valide dans la plage -> pas de requete de conflits")
    void previewRows_noValidAddressSkipsDatabaseLookup() {
        SeriesReservationResponse response = service.previewRows(
                rows(row("10.10.11.5", "a"), row("x", "b")));

        assertThat(response.canCreate()).isFalse();
        verify(addressRepository, never()).findOccupiedIpsBetween(any(), any(), any());
    }

    @Test
    @DisplayName("previewRows : liste vide, trop longue ou nom trop long refuses")
    void previewRows_invalidListsAreRejected() {
        assertThatThrownBy(() -> service.previewRows(rows()))
                .isInstanceOf(InvalidSeriesException.class);

        SeriesRowsRequest.Row[] tooMany = new SeriesRowsRequest.Row[257];
        java.util.Arrays.fill(tooMany, row("10.10.10.21", "a"));
        assertThatThrownBy(() -> service.previewRows(rows(tooMany)))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("256");

        assertThatThrownBy(() -> service.previewRows(rows(row("10.10.10.21", "x".repeat(101)))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("100");

        assertThatThrownBy(() -> service.previewRows(rows((SeriesRowsRequest.Row) null)))
                .isInstanceOf(InvalidSeriesException.class);
    }

    @Test
    @DisplayName("createRows : toutes les lignes sont enregistrees en un lot avec leurs attributs")
    void createRows_savesAllRowsInOneBatch() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.30"))
                .thenReturn(List.of());
        SeriesRowsRequest request = rows(
                new SeriesRowsRequest.Row("10.10.10.30", "NTNX-CVM-01", "CVM 1", "AA:BB:CC:DD:EE:FF", true),
                new SeriesRowsRequest.Row("10.10.10.21", "  ", "   ", " ", null));

        SeriesReservationResponse response = service.createRows(request, "nahim");

        assertThat(response.canCreate()).isTrue();
        verify(addressRepository).saveAllAndFlush(savedCaptor.capture());
        List<Address> saved = savedCaptor.getValue();
        assertThat(saved).extracting(Address::getAddress).containsExactly("10.10.10.30", "10.10.10.21");
        assertThat(saved).extracting(Address::getHostname).containsExactly("NTNX-CVM-01", null);
        assertThat(saved).extracting(Address::getDescription).containsExactly("CVM 1", null);
        assertThat(saved).extracting(Address::getMac).containsExactly("aa:bb:cc:dd:ee:ff", null);
        assertThat(saved).extracting(Address::isTemporary).containsExactly(true, false);
        assertThat(saved).allSatisfy(a -> {
            assertThat(a.getSubnet()).isSameAs(subnet);
            assertThat(a.getContext()).isSameAs(subnet.getContext());
            assertThat(a.getSite()).isSameAs(subnet.getSite());
            assertThat(a.getModifiedBy()).isEqualTo("nahim");
            assertThat(a.getDiscoverySource()).isEqualTo("manual");
        });
    }

    @Test
    @DisplayName("createRows : une ligne non libre -> SeriesConflictException avec statuts, rien n'est cree")
    void createRows_conflictCreatesNothing() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.22"))
                .thenReturn(List.of("10.10.10.22"));

        assertThatThrownBy(() -> service.createRows(
                rows(row("10.10.10.21", "a"), row("10.10.10.22", "b")), "nahim"))
                .isInstanceOfSatisfying(SeriesConflictException.class, e -> {
                    assertThat(e.getMessage()).contains("1 address(es)").contains("no address was created");
                    assertThat(e.getPreview().rows()).extracting(SeriesReservationResponse.Entry::status)
                            .containsExactly("AVAILABLE", "OCCUPIED");
                });

        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("createRows : violation d'unicite concurrente -> ConflictException (transaction annulee)")
    void createRows_concurrentInsertIsReportedAsConflict() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.21"))
                .thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("uq_addresses_address_subnet"))
                .when(addressRepository).saveAllAndFlush(any());

        assertThatThrownBy(() -> service.createRows(rows(row("10.10.10.21", "a")), "nahim"))
                .isInstanceOf(ConflictException.class)
                .isNotInstanceOf(SeriesConflictException.class)
                .hasMessageContaining("concurrently");
    }

    // -------------------------------------------------------
    // sous-reseau par groupe / par ligne, IP de depart automatique
    // -------------------------------------------------------

    private static final Long MGMT_ID = 6L;

    private Subnet addSubnet(Long id, String network, Long contextId) {
        Subnet other = buildSubnet(network, null);
        other.setId(id);
        other.getContext().setId(contextId);
        lenient().when(subnetService.getEntityById(id)).thenReturn(other);
        return other;
    }

    private static SeriesGroupsRequest.Group groupIn(Long subnetId, String startIp, int count, String prefix) {
        return new SeriesGroupsRequest.Group("G", count, null, prefix, null, null, null, startIp, subnetId);
    }

    @Test
    @DisplayName("previewGroups : chaque groupe est calcule dans son propre sous-reseau")
    void previewGroups_eachGroupInItsOwnSubnet() {
        addSubnet(MGMT_ID, "10.10.20.0/24", 1L);
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.21", "10.10.10.22"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(MGMT_ID, "10.10.20.11", "10.10.20.12"))
                .thenReturn(List.of("10.10.20.12"));

        SeriesGroupsResponse response = service.previewGroups(groups("10.10.10.21", 0, null,
                group("HOST", 2, "H-"), groupIn(MGMT_ID, "10.10.20.11", 2, "IPMI-")));

        assertThat(response.network()).isEqualTo("10.10.10.0/24");
        assertThat(response.canCreate()).isFalse();
        assertThat(response.groups().get(0).subnetId()).isEqualTo(SUBNET_ID);
        assertThat(response.groups().get(0).network()).isEqualTo("10.10.10.0/24");
        assertThat(response.groups().get(1).subnetId()).isEqualTo(MGMT_ID);
        assertThat(response.groups().get(1).network()).isEqualTo("10.10.20.0/24");
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.20.11", "10.10.20.12");
        assertThat(response.groups().get(1).rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("AVAILABLE", "OCCUPIED");
    }

    @Test
    @DisplayName("createGroups : chaque adresse est enregistree dans le sous-reseau de son groupe")
    void createGroups_savesEachRowInItsSubnet() {
        Subnet mgmt = addSubnet(MGMT_ID, "10.10.20.0/24", 1L);

        SeriesGroupsResponse response = service.createGroups(groups("10.10.10.21", 0, null,
                group("HOST", 2, "H-"), groupIn(MGMT_ID, "10.10.20.11", 2, "IPMI-")), "nahim");

        assertThat(response.canCreate()).isTrue();
        verify(addressRepository).saveAllAndFlush(savedCaptor.capture());
        List<Address> saved = savedCaptor.getValue();
        assertThat(saved).extracting(Address::getAddress)
                .containsExactly("10.10.10.21", "10.10.10.22", "10.10.20.11", "10.10.20.12");
        assertThat(saved).extracting(Address::getSubnet)
                .containsExactly(subnet, subnet, mgmt, mgmt);
    }

    @Test
    @DisplayName("previewGroups : sans IP de depart, le groupe prend le premier bloc libre du sous-reseau")
    void previewGroups_autoStartPicksFirstFreeBlock() {
        addSubnet(MGMT_ID, "10.10.20.0/24", 1L);
        when(addressRepository.findOccupiedIpsBetween(MGMT_ID, "10.10.20.1", "10.10.20.254"))
                .thenReturn(List.of("10.10.20.1", "10.10.20.2", "10.10.20.4"));
        when(addressRepository.findOccupiedIpsBetween(MGMT_ID, "10.10.20.5", "10.10.20.7"))
                .thenReturn(List.of());

        SeriesGroupsResponse response = service.previewGroups(groups(null, 0, null,
                groupIn(MGMT_ID, null, 3, "IPMI-")));

        assertThat(response.canCreate()).isTrue();
        assertThat(response.groups().get(0).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.20.5", "10.10.20.6", "10.10.20.7");
    }

    @Test
    @DisplayName("previewGroups : sans IP de depart en mode skip, la premiere adresse libre est retenue")
    void previewGroups_autoStartInSkipModeUsesFirstFreeAddress() {
        addSubnet(MGMT_ID, "10.10.20.0/24", 1L);
        when(addressRepository.findOccupiedIpsBetween(MGMT_ID, "10.10.20.1", "10.10.20.254"))
                .thenReturn(List.of("10.10.20.1", "10.10.20.2"));
        when(addressRepository.findOccupiedIpsBetween(MGMT_ID, "10.10.20.3", "10.10.20.254"))
                .thenReturn(List.of());

        SeriesGroupsResponse response = service.previewGroups(groups(null, 0, "skip",
                groupIn(MGMT_ID, null, 2, "IPMI-")));

        assertThat(response.groups().get(0).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.20.3", "10.10.20.4");
    }

    @Test
    @DisplayName("previewGroups : sans IP de depart ni demande, le premier groupe prend le premier bloc libre")
    void previewGroups_autoStartWithoutRequestStartIp() {
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.1", "10.10.10.254"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.1", "10.10.10.2"))
                .thenReturn(List.of());

        SeriesGroupsResponse response = service.previewGroups(groups(null, 0, null,
                group("HOST", 2, "H-")));

        assertThat(response.canCreate()).isTrue();
        assertThat(response.groups().get(0).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.1", "10.10.10.2");
    }

    @Test
    @DisplayName("previewGroups : le choix automatique evite les adresses deja prevues par un groupe precedent")
    void previewGroups_autoStartAvoidsAddressesPlannedEarlier() {
        Long otherId = 7L;
        addSubnet(otherId, "10.10.30.0/24", 1L);
        // groupe 1 : 10.10.10.1-2 (explicite) ; groupe 2 : autre sous-reseau (auto) ;
        // groupe 3 : retour dans le sous-reseau 5, sans depart -> auto, apres .1 et .2
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.1", "10.10.10.2"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(otherId, "10.10.30.1", "10.10.30.254"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(otherId, "10.10.30.1", "10.10.30.1"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.1", "10.10.10.254"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.3", "10.10.10.3"))
                .thenReturn(List.of());

        SeriesGroupsResponse response = service.previewGroups(groups("10.10.10.1", 0, null,
                group("HOST", 2, "H-"),
                groupIn(otherId, null, 1, "X-"),
                groupIn(null, null, 1, "Y-")));

        assertThat(response.groups().get(2).rows()).extracting(SeriesReservationResponse.Entry::ip)
                .containsExactly("10.10.10.3");
        assertThat(response.canCreate()).isTrue();
    }

    @Test
    @DisplayName("previewGroups : aucun bloc libre assez grand -> InvalidSeriesException")
    void previewGroups_noFreeBlockIsRejected() {
        addSubnet(MGMT_ID, "10.10.20.0/30", 1L);

        assertThatThrownBy(() -> service.previewGroups(groups(null, 0, null,
                groupIn(MGMT_ID, null, 3, "IPMI-"))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("No free block");
    }

    @Test
    @DisplayName("previewGroups : un sous-reseau d'un autre contexte est refuse")
    void previewGroups_subnetOfAnotherContextIsRejected() {
        addSubnet(MGMT_ID, "10.10.20.0/24", 99L);

        assertThatThrownBy(() -> service.previewGroups(groups("10.10.10.21", 0, null,
                group("HOST", 1, "H-"), groupIn(MGMT_ID, "10.10.20.11", 1, "IPMI-"))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("same context");
        verify(addressRepository, never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("previewRows : chaque ligne est controlee dans son sous-reseau")
    void previewRows_eachRowInItsSubnet() {
        addSubnet(MGMT_ID, "10.10.20.0/24", 1L);
        when(addressRepository.findOccupiedIpsBetween(SUBNET_ID, "10.10.10.30", "10.10.10.30"))
                .thenReturn(List.of());
        when(addressRepository.findOccupiedIpsBetween(MGMT_ID, "10.10.20.30", "10.10.20.30"))
                .thenReturn(List.of("10.10.20.30"));

        SeriesReservationResponse response = service.previewRows(rows(
                row("10.10.10.30", "H-01"),
                new SeriesRowsRequest.Row("10.10.20.30", "IPMI-01", null, null, null, MGMT_ID),
                new SeriesRowsRequest.Row("10.10.10.31", "IPMI-02", null, null, null, MGMT_ID)));

        assertThat(response.rows()).extracting(SeriesReservationResponse.Entry::status)
                .containsExactly("AVAILABLE", "OCCUPIED", "OUT_OF_RANGE");
        assertThat(response.canCreate()).isFalse();
    }

    @Test
    @DisplayName("createRows : chaque ligne est enregistree dans son sous-reseau")
    void createRows_savesEachRowInItsSubnet() {
        Subnet mgmt = addSubnet(MGMT_ID, "10.10.20.0/24", 1L);

        service.createRows(rows(
                row("10.10.10.30", "H-01"),
                new SeriesRowsRequest.Row("10.10.20.30", "IPMI-01", null, null, null, MGMT_ID)), "nahim");

        verify(addressRepository).saveAllAndFlush(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).extracting(Address::getSubnet).containsExactly(subnet, mgmt);
        assertThat(savedCaptor.getValue()).extracting(Address::getAddress)
                .containsExactly("10.10.10.30", "10.10.20.30");
    }

    @Test
    @DisplayName("previewRows : une ligne d'un autre contexte est refusee")
    void previewRows_subnetOfAnotherContextIsRejected() {
        addSubnet(MGMT_ID, "10.10.20.0/24", 99L);

        assertThatThrownBy(() -> service.previewRows(rows(
                new SeriesRowsRequest.Row("10.10.20.30", "IPMI-01", null, null, null, MGMT_ID))))
                .isInstanceOf(InvalidSeriesException.class)
                .hasMessageContaining("same context");
    }

    // -------------------------------------------------------
    // helpers
    // -------------------------------------------------------

    private static SeriesReservationRequest request(String startIp, int count, String occupiedMode) {
        return new SeriesReservationRequest(
                SUBNET_ID, startIp, count, null, "NTNX-HOST-", null, null, null, occupiedMode, null, null);
    }

    private static SeriesGroupsRequest.Group group(String label, int count, String prefix) {
        return new SeriesGroupsRequest.Group(label, count, null, prefix, null, null, null);
    }

    private static SeriesGroupsRequest groups(String startIp, int gap, String occupiedMode,
                                              SeriesGroupsRequest.Group... groups) {
        return new SeriesGroupsRequest(SUBNET_ID, startIp, gap, occupiedMode, null, null,
                java.util.Arrays.asList(groups));
    }

    private static SeriesRowsRequest.Row row(String ip, String hostname) {
        return new SeriesRowsRequest.Row(ip, hostname, null, null, null);
    }

    private static SeriesRowsRequest rows(SeriesRowsRequest.Row... rows) {
        return new SeriesRowsRequest(SUBNET_ID, java.util.Arrays.asList(rows));
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
