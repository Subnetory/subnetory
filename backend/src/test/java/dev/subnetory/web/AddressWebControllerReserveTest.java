package dev.subnetory.web;

import dev.subnetory.dto.AvailableIpResponse;
import dev.subnetory.dto.BulkUpsertRequest;
import dev.subnetory.dto.BulkUpsertResponse;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.service.ActiveContextService;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.ImportFileValidator;
import dev.subnetory.web.form.BulkReservationForm;
import dev.subnetory.web.form.BulkReservationRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressWebControllerReserveTest {

    @Mock AddressService addressService;
    @Mock SubnetService subnetService;
    @Mock IpAllocService ipAllocService;
    @Mock ObjectProvider<ActiveContextService> activeContextServiceProvider;
    @Mock ImportFileValidator importFileValidator;
    @Mock AuthAuditService authAuditService;

    AddressWebController controller;
    Authentication authentication;

    @BeforeEach
    void setUp() {
        when(activeContextServiceProvider.getIfAvailable()).thenReturn(null);
        controller = new AddressWebController(
                addressService, subnetService, ipAllocService,
                activeContextServiceProvider, importFileValidator, authAuditService);
        authentication = new UsernamePasswordAuthenticationToken("operator", "n/a", List.of());
    }

    // ── GET /reserve ─────────────────────────────────────────────────────

    @Test
    void reserveForm_withSubnetIdParam_prefillsForm() {
        when(subnetService.findAll(any())).thenReturn(Page.empty());
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reserveForm(42L, model);

        assertThat(view).isEqualTo("network/address-reserve");
        BulkReservationForm form = (BulkReservationForm) model.get("form");
        assertThat(form.getSubnetId()).isEqualTo(42L);
        assertThat(form.getRows()).isEmpty();
    }

    // ── POST /reserve/generate ──────────────────────────────────────────

    @Test
    void reserveGenerate_noSubnetSelected_setsFormErrorWithoutCallingService() {
        when(subnetService.findAll(any())).thenReturn(Page.empty());
        BulkReservationForm form = new BulkReservationForm();
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reserveGenerate(form, model);

        assertThat(view).isEqualTo("network/address-reserve");
        assertThat(model.get("formError")).isNotNull();
        verify(ipAllocService, never()).findAvailableIps(anyLong(), anyInt());
    }

    @Test
    void reserveGenerate_appendsNewRowsWithoutDuplicatingExisting() {
        when(subnetService.findAll(any())).thenReturn(Page.empty());
        BulkReservationForm form = new BulkReservationForm();
        form.setSubnetId(3L);
        form.setAdditionalCount(3);
        form.getRows().add(new BulkReservationRow("10.0.0.10"));
        when(ipAllocService.findAvailableIps(3L, 4))
                .thenReturn(new AvailableIpResponse("10.0.0.0/24", 4, 4,
                        List.of("10.0.0.10", "10.0.0.11", "10.0.0.12", "10.0.0.13")));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reserveGenerate(form, model);

        assertThat(view).isEqualTo("network/address-reserve");
        assertThat(model.get("formError")).isNull();
        assertThat(form.getRows()).hasSize(4);
        Set<String> addresses = form.getRows().stream()
                .map(BulkReservationRow::getAddress).collect(java.util.stream.Collectors.toSet());
        assertThat(addresses).containsExactlyInAnyOrder(
                "10.0.0.10", "10.0.0.11", "10.0.0.12", "10.0.0.13");
    }

    @Test
    void reserveGenerate_subnetNotFound_setsFormError() {
        when(subnetService.findAll(any())).thenReturn(Page.empty());
        BulkReservationForm form = new BulkReservationForm();
        form.setSubnetId(999L);
        when(ipAllocService.findAvailableIps(999L, 10))
                .thenThrow(new ResourceNotFoundException("Subnet", 999L));
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reserveGenerate(form, model);

        assertThat(view).isEqualTo("network/address-reserve");
        assertThat(model.get("formError")).isNotNull();
    }

    // ── POST /reserve ────────────────────────────────────────────────────

    @Test
    void reserveSubmit_bindingErrors_redisplaysForm() {
        when(subnetService.findAll(any())).thenReturn(Page.empty());
        BulkReservationForm form = new BulkReservationForm();
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "form");
        errors.reject("error");
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reserveSubmit(form, errors, model, authentication);

        assertThat(view).isEqualTo("network/address-reserve");
        verify(addressService, never()).bulkUpsert(any(), any());
    }

    @Test
    void reserveSubmit_noRowIncluded_setsFormError() {
        when(subnetService.findAll(any())).thenReturn(Page.empty());
        BulkReservationForm form = new BulkReservationForm();
        form.setSubnetId(3L);
        BulkReservationRow row = new BulkReservationRow("10.0.0.10");
        row.setIncluded(false);
        form.getRows().add(row);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "form");
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reserveSubmit(form, errors, model, authentication);

        assertThat(view).isEqualTo("network/address-reserve");
        assertThat(model.get("formError")).isNotNull();
        verify(addressService, never()).bulkUpsert(any(), any());
    }

    @Test
    void reserveSubmit_success_callsBulkUpsertWithIncludedRowsOnly() {
        BulkReservationForm form = new BulkReservationForm();
        form.setSubnetId(3L);
        BulkReservationRow included = new BulkReservationRow("10.0.0.10");
        included.setHostname("srv-01");
        included.setDescription("Serveur");
        included.setMac("aa:bb:cc:dd:ee:ff");
        included.setTemporary(true);
        BulkReservationRow excluded = new BulkReservationRow("10.0.0.11");
        excluded.setIncluded(false);
        form.getRows().add(included);
        form.getRows().add(excluded);
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "form");
        BulkUpsertResponse response = new BulkUpsertResponse(1, 0, 0, List.of());
        when(addressService.bulkUpsert(any(), eq("operator"))).thenReturn(response);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.reserveSubmit(form, errors, model, authentication);

        assertThat(view).isEqualTo("network/address-reserve-result");
        assertThat(model.get("result")).isEqualTo(response);

        org.mockito.ArgumentCaptor<BulkUpsertRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BulkUpsertRequest.class);
        verify(addressService).bulkUpsert(captor.capture(), eq("operator"));
        BulkUpsertRequest sent = captor.getValue();
        assertThat(sent.addresses()).hasSize(1);
        assertThat(sent.addresses().get(0).address()).isEqualTo("10.0.0.10");
        assertThat(sent.addresses().get(0).hostname()).isEqualTo("srv-01");
        assertThat(sent.addresses().get(0).subnetId()).isEqualTo(3L);
        assertThat(sent.override()).isFalse();
    }
}
