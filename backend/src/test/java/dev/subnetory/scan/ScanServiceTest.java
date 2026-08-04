package dev.subnetory.scan;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.domain.Subnet;
import dev.subnetory.service.AddressService;
import dev.subnetory.service.SubnetService;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Correctif sécurité FAIBLE/MOYEN (audit 04/08/2026) : couvre la limitation
 * de concurrence des scans Nmap ({@code globalScanSemaphore}, {@code
 * activeScansByUser}) et le plafond de taille de sortie ({@code
 * maxOutputBytes}). {@code nmapPath} est pointé vers l'exécutable {@code
 * java} de la JVM en cours d'exécution (obtenu via {@link ProcessHandle}),
 * pour simuler un outil "disponible" ({@code assertNmapAvailable} n'exige
 * qu'un code de sortie 0 sur {@code --version}) sans dépendre d'un vrai
 * binaire nmap (absent en CI, même contrainte que {@code ScanControllerIT})
 * — et sans dépendre d'un utilitaire externe type {@code echo}, qui n'existe
 * comme exécutable autonome que sous Linux/macOS (sous Windows, {@code echo}
 * est un built-in du shell, pas un {@code .exe} : {@code ProcessBuilder}
 * échoue à le lancer, quel que soit l'OS hôte des développeurs/CI). {@code
 * java} est garanti présent sur toute plateforme faisant tourner ces tests.
 *
 * <p>Invoqué avec des options nmap qu'il ne reconnaît pas (ex. {@code -sn}),
 * {@code java} échoue systématiquement avec un code de sortie non nul et une
 * sortie standard (stdout) vide — voir les scénarios ci-dessous, qui
 * échouent avant ou pendant la lecture de cette sortie, jamais après.</p>
 */
@ExtendWith(MockitoExtension.class)
class ScanServiceTest {

    @Mock SubnetService subnetService;
    @Mock AddressService addressService;

    ScanService service;
    RestoreMaintenanceGate restoreMaintenanceGate;

    @BeforeEach
    void setUp() {
        restoreMaintenanceGate = new RestoreMaintenanceGate();
        service = new ScanService(subnetService, addressService, restoreMaintenanceGate);
        String javaExecutable = ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException(
                        "Impossible de determiner le chemin de l'executable java courant"));
        ReflectionTestUtils.setField(service, "nmapPath", javaExecutable);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 10);
        ReflectionTestUtils.setField(service, "maxConcurrentScans", 3);
        ReflectionTestUtils.setField(service, "maxConcurrentScansPerUser", 1);
        ReflectionTestUtils.setField(service, "maxOutputBytes", 10_485_760L);
        // @PostConstruct n'est jamais invoqué : ce service est construit
        // directement, hors contexte Spring (même pattern que
        // BackupExecutionServiceTest).
        ReflectionTestUtils.invokeMethod(service, "initScanSemaphore");
    }

    private Subnet sampleSubnet(Long id, String cidr) {
        Subnet subnet = new Subnet();
        setId(subnet, id);
        subnet.setNetwork(cidr);
        return subnet;
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> activeScansByUser() {
        return (Map<String, Integer>) ReflectionTestUtils.getField(service, "activeScansByUser");
    }

    private Semaphore globalSemaphore() {
        return (Semaphore) ReflectionTestUtils.getField(service, "globalScanSemaphore");
    }

    // -------------------------------------------------------
    // Limite par utilisateur
    // -------------------------------------------------------

    @Test
    void scan_userAlreadyAtPerUserLimit_isRejectedWithoutTouchingSubnetOrAddress() {
        when(subnetService.getEntityById(1L)).thenReturn(sampleSubnet(1L, "10.0.0.0/24"));
        // Simule un scan deja en cours pour "alice" (limite par defaut : 1).
        Map<String, Integer> active = new HashMap<>();
        active.put("alice", 1);
        ReflectionTestUtils.setField(service, "activeScansByUser", active);

        ScanRequest request = new ScanRequest("nmap", false);

        assertThatThrownBy(() -> service.scan(1L, request, "alice"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.TOO_MANY_CONCURRENT_SCANS));

        // Aucun upsert d'adresse ne doit avoir ete tente : le rejet intervient
        // avant meme l'execution de nmap.
        verifyNoInteractions(addressService);
        // Le compteur pour "alice" reste a 1 (jamais incremente par la
        // tentative refusee), et n'affecte pas les autres utilisateurs.
        assertThat(activeScansByUser()).containsExactly(Map.entry("alice", 1));
    }

    @Test
    void scan_differentUsers_areTrackedIndependently() {
        when(subnetService.getEntityById(1L)).thenReturn(sampleSubnet(1L, "10.0.0.0/24"));
        Map<String, Integer> active = new HashMap<>();
        active.put("alice", 1);
        ReflectionTestUtils.setField(service, "activeScansByUser", active);

        ScanRequest request = new ScanRequest("nmap", false);

        // "bob" n'a aucun scan en cours : sa tentative doit dépasser la
        // limite par utilisateur uniquement si SES propres scans l'atteignent,
        // pas ceux d'un autre utilisateur. Le controle de concurrence la
        // laisse donc passer jusqu'a l'execution reelle de "nmap" (ici
        // "java", invoque avec des options nmap qu'il ne reconnait pas :
        // code de sortie non nul, stdout vide → EXECUTION_FAILED) : la
        // preuve que TOO_MANY_CONCURRENT_SCANS n'a pas ete leve pour "bob".
        assertThatThrownBy(() -> service.scan(1L, request, "bob"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.EXECUTION_FAILED));
    }

    // -------------------------------------------------------
    // Limite globale
    // -------------------------------------------------------

    @Test
    void scan_globalSemaphoreExhausted_isRejectedWithoutTouchingSubnetOrAddress() throws Exception {
        when(subnetService.getEntityById(1L)).thenReturn(sampleSubnet(1L, "10.0.0.0/24"));
        // Epuise tous les permis globaux (3 par defaut dans ce test) avant
        // meme d'appeler scan() : simule 3 scans deja en cours par d'autres
        // utilisateurs.
        Semaphore semaphore = globalSemaphore();
        semaphore.acquire(3);

        ScanRequest request = new ScanRequest("nmap", false);

        assertThatThrownBy(() -> service.scan(1L, request, "carol"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.TOO_MANY_CONCURRENT_SCANS));

        verifyNoInteractions(addressService);
        // Le slot par-utilisateur de "carol" a ete relache malgre l'echec
        // (pas de fuite) : aucune entree ne doit subsister pour elle.
        assertThat(activeScansByUser()).doesNotContainKey("carol");
    }

    @Test
    void scan_afterRelease_globalSemaphoreAcceptsNewScanAgain() throws Exception {
        when(subnetService.getEntityById(1L)).thenReturn(sampleSubnet(1L, "10.0.0.0/24"));
        Semaphore semaphore = globalSemaphore();
        semaphore.acquire(3);
        semaphore.release(); // un scan "se termine", liberant un permis

        ScanRequest request = new ScanRequest("nmap", false);

        // N'echoue plus par TOO_MANY_CONCURRENT_SCANS (le permis liberé est
        // disponible) — echoue ensuite avec EXECUTION_FAILED (options nmap
        // non reconnues par "java", cf. commentaire de classe), preuve que
        // le controle de concurrence a bien laisse passer cette tentative
        // jusqu'a l'execution reelle.
        assertThatThrownBy(() -> service.scan(1L, request, "dave"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.EXECUTION_FAILED));
    }

    // -------------------------------------------------------
    // Plafond de taille de sortie
    // -------------------------------------------------------

    @Test
    void scan_outputExceedsConfiguredLimit_failsWithExecutionFailed() {
        when(subnetService.getEntityById(1L)).thenReturn(sampleSubnet(1L, "10.0.0.0/24"));
        // Le message d'erreur "Unrecognized option: ..." ecrit par "java"
        // sur stderr (options nmap non reconnues) depasse largement une
        // limite de quelques octets.
        ReflectionTestUtils.setField(service, "maxOutputBytes", 5L);

        ScanRequest request = new ScanRequest("nmap", false);

        assertThatThrownBy(() -> service.scan(1L, request, "erin"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.EXECUTION_FAILED))
                .hasMessageContaining("nmap output");
    }

    // -------------------------------------------------------
    // Sonde nmap --version deplacee apres les quotas (correctif securite
    // FAIBLE, second audit externe 04/08/2026)
    // -------------------------------------------------------

    @Test
    void scan_globalSemaphoreExhausted_neverProbesNmapAvailability() throws Exception {
        when(subnetService.getEntityById(1L)).thenReturn(sampleSubnet(1L, "10.0.0.0/24"));
        // nmapPath pointe vers un chemin inexistant : si assertNmapAvailable()
        // etait encore appelee AVANT le controle de concurrence (comportement
        // avant correctif), elle echouerait avec TOOL_NOT_AVAILABLE. Le fait
        // d'obtenir TOO_MANY_CONCURRENT_SCANS a la place prouve que le quota
        // est verifie en premier, sans jamais lancer ce process de sonde.
        ReflectionTestUtils.setField(service, "nmapPath", "/no/such/nmap/binary/here");
        Semaphore semaphore = globalSemaphore();
        semaphore.acquire(3);

        ScanRequest request = new ScanRequest("nmap", false);

        assertThatThrownBy(() -> service.scan(1L, request, "grace"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.TOO_MANY_CONCURRENT_SCANS));

        verifyNoInteractions(addressService);
    }

    @Test
    void scan_perUserLimitExhausted_neverProbesNmapAvailability() {
        when(subnetService.getEntityById(1L)).thenReturn(sampleSubnet(1L, "10.0.0.0/24"));
        ReflectionTestUtils.setField(service, "nmapPath", "/no/such/nmap/binary/here");
        Map<String, Integer> active = new HashMap<>();
        active.put("heidi", 1);
        ReflectionTestUtils.setField(service, "activeScansByUser", active);

        ScanRequest request = new ScanRequest("nmap", false);

        assertThatThrownBy(() -> service.scan(1L, request, "heidi"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.TOO_MANY_CONCURRENT_SCANS));

        verifyNoInteractions(addressService);
    }

    // -------------------------------------------------------
    // Restauration en cours (correctif securite FAIBLE, second audit
    // externe 04/08/2026)
    // -------------------------------------------------------

    @Test
    void scan_restoreAlreadyInProgress_isRejectedBeforeTouchingSubnetOrNmap() {
        // Verification precoce (avant meme la lecture du subnet) : aucun
        // scan Nmap ne doit demarrer si une restauration est deja en cours
        // au moment de l'appel.
        restoreMaintenanceGate.begin();

        ScanRequest request = new ScanRequest("nmap", false);

        assertThatThrownBy(() -> service.scan(1L, request, "frank"))
                .isInstanceOf(ScanException.class)
                .satisfies(e -> assertThat(((ScanException) e).getReason())
                        .isEqualTo(ScanException.Reason.RESTORE_IN_PROGRESS));

        // Rejet immediat : ni le subnet ni l'adresse ne sont touches.
        verifyNoInteractions(subnetService);
        verifyNoInteractions(addressService);
    }
}
