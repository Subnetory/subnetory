package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.csv.AddressCsvParser;
import dev.subnetory.csv.AddressXlsxParser;
import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Subnet;
import dev.subnetory.repository.AddressRepository;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AddressServiceContextImportTest {

    @Mock AddressRepository addressRepository;
    @Mock SubnetService subnetService;
    @Mock AddressCsvParser csvParser;
    @Mock AddressXlsxParser xlsxParser;
    @Mock ContextAccessService contextAccessService;

    AddressService service;

    @BeforeEach
    void setUp() {
        service = new AddressService(
                addressRepository,
                subnetService,
                csvParser,
                xlsxParser,
                contextAccessService);
    }

    @Test
    void importCsv_withRequiredContext_rejectsSubnetFromAnotherContext() throws Exception {
        Subnet subnetFromOtherContext = buildSubnet(10L, "192.168.10.0/24", 99L);
        var row = new AddressCsvParser.CsvRow(
                1,
                "192.168.10.42",
                10L,
                null,
                null,
                "srv-client",
                null,
                false,
                "csv");

        when(csvParser.parse(any())).thenReturn(new AddressCsvParser.ParseResult(List.of(row), List.of()));
        when(subnetService.findEntityByIdOptional(10L)).thenReturn(Optional.of(subnetFromOtherContext));

        var result = service.importCsv(
                new ByteArrayInputStream(new byte[0]),
                false,
                "admin",
                2L);

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.created()).isZero();
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.errorDetails()).singleElement()
                .satisfies(error -> {
                    assertThat(error.row()).isEqualTo(1);
                    assertThat(error.address()).isEqualTo("192.168.10.42");
                    assertThat(error.reason()).contains("contexte actif");
                });

        verify(contextAccessService).requireAccess(2L);
        verify(addressRepository, never()).save(any());
    }

    private static Subnet buildSubnet(Long id, String network, Long contextId) {
        NetworkContext context = new NetworkContext();
        context.setId(contextId);

        Subnet subnet = new Subnet();
        subnet.setId(id);
        subnet.setNetwork(network);
        subnet.setContext(context);
        return subnet;
    }
}
