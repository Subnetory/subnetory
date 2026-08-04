package dev.subnetory.service;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Site;
import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SiteResponse;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.SiteRepository;
import dev.subnetory.repository.SubnetRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SiteService {

    private final SiteRepository siteRepository;
    private final NetworkContextService contextService;
    private final ContextAccessService contextAccessService;
    private final SubnetRepository subnetRepository;

    public SiteService(SiteRepository siteRepository,
                       NetworkContextService contextService,
                       ContextAccessService contextAccessService,
                       SubnetRepository subnetRepository) {
        this.siteRepository = siteRepository;
        this.contextService = contextService;
        this.contextAccessService = contextAccessService;
        this.subnetRepository = subnetRepository;
    }

    public Page<SiteResponse> findAll(Pageable pageable) {
        var allowedIds = contextAccessService.allowedContextIds();
        if (allowedIds.isEmpty()) return Page.empty(pageable);
        return siteRepository.findByContextIdIn(allowedIds, pageable).map(this::toResponse);
    }

    public Page<SiteResponse> findByContext(Long contextId, Pageable pageable) {
        contextAccessService.requireAccess(contextId);
        return siteRepository.findByContextId(contextId, pageable).map(this::toResponse);
    }

    public SiteResponse findById(Long id) {
        return toResponse(getEntityById(id));
    }

    public Site getEntityById(Long id) {
        Site site = siteRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Site", id));
        contextAccessService.requireResourceAccess(site.getContext().getId(), "Site", id);
        return site;
    }

    @Transactional
    public SiteResponse create(SiteRequest request) {
        // Autoriser le contexte parent AVANT toute verification d'unicite
        // (correctif securite FAIBLE, audit 04/08/2026) : contextService.
        // getEntityById() refuse de maniere identique (404, cf.
        // ContextAccessService#requireAccess "sans reveler si le contexte
        // existe reellement") que contextId soit inexistant ou simplement
        // hors du perimetre de l'utilisateur. Verifier l'unicite du code
        // AVANT ce controle d'acces (ordre precedent) permettait a un
        // utilisateur sans acces a un contexte de distinguer un code de site
        // deja pris (409 Conflict, avant meme d'atteindre le controle
        // d'acces) d'un code libre (404 ensuite, une fois le controle
        // d'acces atteint) — un canal lateral revelant l'existence de codes
        // de sites en dehors de son perimetre. Desormais, un utilisateur
        // sans acces recoit toujours 404 en premier, quel que soit l'etat du
        // code demande.
        NetworkContext context = contextService.getEntityById(request.contextId());
        // Normalisation avant verification d'unicite (audit 02/08/2026,
        // correctif ELEVEE) : le code est toujours stocke en majuscules
        // (site.setCode(...toUpperCase()) plus bas), mais existsByCode()
        // comparait jusqu'ici le code BRUT non normalise. Un code saisi en
        // minuscules/casse mixte (le formulaire web, contrairement a l'API,
        // n'imposait aucun format) pouvait donc passer ce pre-controle sans
        // collision detectee, puis echouer plus loin sur la contrainte
        // d'unicite reelle en base (DataIntegrityViolationException, 500 non
        // gere cote controleur). Normaliser ici rend le pre-controle fiable
        // dans tous les cas, en plus du filtrage de format ajoute a SiteForm.
        String normalizedCode = request.code().toUpperCase();
        if (siteRepository.existsByCode(normalizedCode)) {
            throw new ConflictException("Site with code '" + normalizedCode + "' already exists");
        }
        Site site = new Site();
        site.setName(request.name());
        site.setCode(normalizedCode);
        site.setContext(context);
        return toResponse(siteRepository.save(site));
    }

    @Transactional
    public SiteResponse update(Long id, SiteRequest request) {
        Site site = getEntityById(id);
        // Meme normalisation que create() ci-dessus, y compris pour la
        // comparaison "changement reel ?" : sans elle, un code resoumis avec
        // une casse differente de celle deja stockee (toujours majuscules)
        // etait a tort considere comme un changement, declenchant une
        // verification d'unicite inutile (sans consequence ici puisque
        // c'est le meme site, mais incohérent avec la normalisation).
        String normalizedCode = request.code().toUpperCase();
        if (!site.getCode().equals(normalizedCode) && siteRepository.existsByCode(normalizedCode)) {
            throw new ConflictException("Site with code '" + normalizedCode + "' already exists");
        }
        NetworkContext context = contextService.getEntityById(request.contextId());
        // Audit 03/08/2026, correctif BLOQUANT : les sous-reseaux stockent
        // leur propre context_id (jamais resynchronise apres coup). Changer
        // le contexte d'un site qui a encore des sous-reseaux les laisserait
        // associes a l'ancien contexte, exposant une fuite entre perimetres
        // (voir SubnetRepository#findBySiteIdAndContextIdIn). Bloque tant
        // qu'une migration explicite du graphe n'est pas implementee.
        if (!site.getContext().getId().equals(context.getId())
                && subnetRepository.existsBySiteId(id)) {
            throw new ConflictException(
                    "Impossible de changer le contexte d'un site qui contient encore des "
                            + "sous-reseaux. Deplacez ou supprimez d'abord ses sous-reseaux.");
        }
        site.setName(request.name());
        site.setCode(normalizedCode);
        site.setContext(context);
        return toResponse(siteRepository.save(site));
    }

    @Transactional
    public void delete(Long id) {
        Site site = getEntityById(id);
        siteRepository.delete(site);
    }

    // --- mapping ---

    public SiteResponse toResponse(Site site) {
        return new SiteResponse(
                site.getId(),
                site.getName(),
                site.getCode(),
                site.getContext().getId(),
                site.getContext().getName(),
                site.getCreatedAt(),
                site.getUpdatedAt()
        );
    }
}
