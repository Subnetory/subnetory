package dev.subnetory.service;

import dev.subnetory.dto.AddressResponse;
import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.dto.SiteResponse;
import dev.subnetory.dto.SubnetResponse;
import dev.subnetory.dto.VlanResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recherche globale (bandeau) : interroge chaque type d'objet avec les recherches
 * paginées existantes, en ne ramenant que quelques résultats par type.
 *
 * <p>Aucune requête propre : les contextes autorisés sont imposés par chaque service
 * ({@link ContextAccessService}). Le contexte actif ne fait que restreindre davantage.</p>
 */
@Service
@Transactional(readOnly = true)
public class GlobalSearchService {

    /** Nombre maximal de résultats affichés par type. */
    public static final int LIMIT_PER_TYPE = 5;

    private final NetworkContextService contextService;
    private final SiteService siteService;
    private final VlanService vlanService;
    private final SubnetService subnetService;
    private final AddressService addressService;

    public GlobalSearchService(NetworkContextService contextService,
                               SiteService siteService,
                               VlanService vlanService,
                               SubnetService subnetService,
                               AddressService addressService) {
        this.contextService = contextService;
        this.siteService = siteService;
        this.vlanService = vlanService;
        this.subnetService = subnetService;
        this.addressService = addressService;
    }

    public record Result(
            Page<NetworkContextResponse> contexts,
            Page<SiteResponse> sites,
            Page<VlanResponse> vlans,
            Page<SubnetResponse> subnets,
            Page<AddressResponse> addresses) {

        public long total() {
            return contexts.getTotalElements() + sites.getTotalElements() + vlans.getTotalElements()
                    + subnets.getTotalElements() + addresses.getTotalElements();
        }
    }

    public Result search(String q, Long activeContextId) {
        return new Result(
                contextService.search(q, page("name")),
                siteService.search(q, activeContextId, page("code")),
                vlanService.search(q, activeContextId, null, page("vid")),
                subnetService.search(q, activeContextId, null, null, page("network")),
                addressService.search(null, null, null, q, null, activeContextId, null, page("address")));
    }

    private static Pageable page(String sortProperty) {
        return PageRequest.of(0, LIMIT_PER_TYPE, Sort.by(sortProperty));
    }
}
