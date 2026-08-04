package dev.subnetory.web;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.config.SecurityConfig;
import dev.subnetory.dto.AddressResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.IpAllocService;
import dev.subnetory.service.SubnetService;
import dev.subnetory.util.ImportFileValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AddressWebController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, ImportFileValidator.class})
class AddressCrudWebIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean AddressService addressService;
    @MockitoBean SubnetService subnetService;
    @MockitoBean IpAllocService ipAllocService;
    @MockitoBean AuthAuditService authAuditService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SubnetoryUserDetailsService userDetailsService;

    // Beans ajoutes par Sprint 2.13 / T4.
    // Necessaires ici car @WebMvcTest ne charge pas tout le contexte applicatif.
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean ApiRateLimiter apiRateLimiter;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RateLimitingAuthenticationFailureHandler failureHandler;
    @MockitoBean RateLimitingAuthenticationSuccessHandler successHandler;
    // Correctif securite MOYENNE (audit 04/08/2026) : RestoreMaintenanceFilter,
    // cable dans SecurityConfig#webFilterChain, a besoin de ce bean.
    @MockitoBean RestoreMaintenanceGate restoreMaintenanceGate;
    private AddressResponse sampleAddress;

    @BeforeEach
    void setUp() {
        sampleAddress = new AddressResponse(
                1L, "192.168.1.10", "aa:bb:cc:dd:ee:ff", "srv-web-01", "Serveur web",
                10L, "Production", 20L, "PAR01", 30L, "192.168.1.0/24",
                "admin", false, null, "manual",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(addressService.search(any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(subnetService.findAll(any(Pageable.class))).thenReturn(Page.empty());
    }

    // â”€â”€ AccÃ¨s anonyme â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void getNewForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/addresses/new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void getEditForm_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(get("/network/addresses/1/edit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void postCreate_anonymous_redirectsToLogin() throws Exception {
        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "192.168.1.10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // â”€â”€ CSRF â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "IP")
    void postCreate_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/addresses")
                        .param("address", "192.168.1.10")
                        .param("subnetId", "30"))
                .andExpect(status().isForbidden());

        verify(addressService, never()).create(any(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/network/addresses/1/delete"))
                .andExpect(status().isForbidden());

        verify(addressService, never()).delete(any());
    }

    // â”€â”€ GET formulaire crÃ©ation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "IP")
    void getNewForm_roleIp_returns200() throws Exception {
        mvc.perform(get("/network/addresses/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeExists("form", "allSubnets"))
                .andExpect(model().attribute("pageTitle", "Nouvelle adresse IP"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getNewForm_roleAdmin_returns200() throws Exception {
        mvc.perform(get("/network/addresses/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"));
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void getNewForm_roleNetworkOnly_returns403() throws Exception {
        mvc.perform(get("/network/addresses/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void list_roleReadOnly_returns200() throws Exception {
        mvc.perform(get("/network/addresses"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/addresses"));
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void getNewForm_roleReadOnly_returns403() throws Exception {
        mvc.perform(get("/network/addresses/new"))
                .andExpect(status().isForbidden());
    }

    // â”€â”€ POST crÃ©ation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "IP", username = "testuser")
    void postCreate_validData_redirectsToListWithFlash() throws Exception {
        when(addressService.create(any(), eq("testuser"))).thenReturn(sampleAddress);

        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "192.168.1.10")
                        .param("subnetId", "30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(addressService).create(any(), eq("testuser"));
        verify(authAuditService).recordAddressCreated(
                eq("testuser"), eq(sampleAddress.id()), eq(sampleAddress.address()));
    }

    @Test
    @WithMockUser(roles = "IP")
    void postCreate_blankAddress_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "")
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeHasFieldErrors("form", "address"));

        verify(addressService, never()).create(any(), anyString());
    }

    @Test
    @WithMockUser(roles = "IP")
    void postCreate_invalidIpFormat_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "999.999.999.999")
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeHasFieldErrors("form", "address"));

        verify(addressService, never()).create(any(), anyString());
    }

    @Test
    @WithMockUser(roles = "IP")
    void postCreate_nullSubnetId_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "192.168.1.10"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeHasFieldErrors("form", "subnetId"));

        verify(addressService, never()).create(any(), anyString());
    }

    @Test
    @WithMockUser(roles = "IP")
    void postCreate_ipAlreadyAssigned_reRendersFormWithBusinessError() throws Exception {
        when(addressService.create(any(), anyString()))
                .thenThrow(new ConflictException("Address 192.168.1.10 is already assigned"));

        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "192.168.1.10")
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeExists("formError", "allSubnets"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void postCreate_ipNotInSubnet_reRendersFormWithBusinessError() throws Exception {
        when(addressService.create(any(), anyString()))
                .thenThrow(new ConflictException("Address 10.1.1.1 is not in subnet 192.168.1.0/24"));

        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "10.1.1.1")
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeExists("formError"));
    }

    @Test
    @WithMockUser(roles = "IP", username = "testuser")
    void postCreate_withAllOptionalFields_redirectsToList() throws Exception {
        when(addressService.create(any(), eq("testuser"))).thenReturn(sampleAddress);

        mvc.perform(post("/network/addresses")
                        .with(csrf())
                        .param("address", "192.168.1.10")
                        .param("subnetId", "30")
                        .param("hostname", "srv-web-01")
                        .param("mac", "aa:bb:cc:dd:ee:ff")
                        .param("description", "Serveur web")
                        .param("temporary", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses"));
    }

    // â”€â”€ GET formulaire Ã©dition â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "IP")
    void getEditForm_existingId_returns200WithPrefilledForm() throws Exception {
        when(addressService.findById(1L)).thenReturn(sampleAddress);

        mvc.perform(get("/network/addresses/1/edit"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeExists("form", "allSubnets"))
                .andExpect(model().attribute("pageTitle", "Modifier l'adresse IP"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void getEditForm_unknownId_returns404() throws Exception {
        when(addressService.findById(999L))
                .thenThrow(new ResourceNotFoundException("Address", 999L));

        mvc.perform(get("/network/addresses/999/edit"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    // â”€â”€ POST Ã©dition â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "IP", username = "testuser")
    void postUpdate_validData_redirectsToDetailWithFlash() throws Exception {
        when(addressService.update(eq(1L), any(), eq("testuser"))).thenReturn(sampleAddress);

        mvc.perform(post("/network/addresses/1")
                        .with(csrf())
                        .param("address", "192.168.1.10")
                        .param("subnetId", "30"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses/1"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void postUpdate_blankAddress_reRendersFormWithError() throws Exception {
        mvc.perform(post("/network/addresses/1")
                        .with(csrf())
                        .param("address", "")
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeHasFieldErrors("form", "address"));

        verify(addressService, never()).update(any(), any(), anyString());
    }

    @Test
    @WithMockUser(roles = "IP")
    void postUpdate_unknownId_returns404() throws Exception {
        when(addressService.update(eq(999L), any(), anyString()))
                .thenThrow(new ResourceNotFoundException("Address", 999L));

        mvc.perform(post("/network/addresses/999")
                        .with(csrf())
                        .param("address", "192.168.1.10")
                        .param("subnetId", "30"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void postUpdate_conflict_reRendersFormWithBusinessError() throws Exception {
        when(addressService.update(eq(1L), any(), anyString()))
                .thenThrow(new ConflictException("Address already assigned"));

        mvc.perform(post("/network/addresses/1")
                        .with(csrf())
                        .param("address", "192.168.1.11")
                        .param("subnetId", "30"))
                .andExpect(status().isOk())
                .andExpect(view().name("network/address-form"))
                .andExpect(model().attributeExists("formError"));
    }

    // â”€â”€ POST suppression â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @WithMockUser(roles = "ADMIN", username = "testadmin")
    void postDelete_success_redirectsToListWithFlash() throws Exception {
        when(addressService.findById(1L)).thenReturn(sampleAddress);
        doNothing().when(addressService).delete(1L);

        mvc.perform(post("/network/addresses/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(addressService).delete(1L);
        verify(authAuditService).recordAddressDeleted(
                eq("testadmin"), eq(1L), eq(sampleAddress.address()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void postDelete_unknownId_redirectsWithFlashError() throws Exception {
        doThrow(new ResourceNotFoundException("Address", 999L))
                .when(addressService).delete(999L);

        mvc.perform(post("/network/addresses/999/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void postDelete_roleIpOnly_success_redirectsToListWithFlash() throws Exception {
        when(addressService.findById(1L)).thenReturn(sampleAddress);
        doNothing().when(addressService).delete(1L);

        mvc.perform(post("/network/addresses/1/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/network/addresses"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(addressService).delete(1L);
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void postDelete_roleNetworkOnly_returns403() throws Exception {
        mvc.perform(post("/network/addresses/1/delete").with(csrf()))
                .andExpect(status().isForbidden());

        verify(addressService, never()).delete(any());
    }

    @Test
    @WithMockUser(roles = "READ_ONLY")
    void postDelete_roleReadOnly_returns403() throws Exception {
        mvc.perform(post("/network/addresses/1/delete").with(csrf()))
                .andExpect(status().isForbidden());

        verify(addressService, never()).delete(any());
    }
}
