package dev.subnetory.backup;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctif securite MOYENNE (audit 04/08/2026) : voir
 * {@link RestoreMaintenanceGate} pour le contexte complet. Tests de la
 * barriere de drainage ajoutes le 04/08/2026 (troisieme audit externe,
 * constat M-01).
 */
class RestoreMaintenanceGateTest {

    @Test
    void isActive_falseByDefault() {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();

        assertThat(gate.isActive()).isFalse();
    }

    @Test
    void begin_activatesGate() {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();

        gate.begin();

        assertThat(gate.isActive()).isTrue();
    }

    @Test
    void end_deactivatesGate() {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.begin();

        gate.end();

        assertThat(gate.isActive()).isFalse();
    }

    @Test
    void tryAdmitMutation_succeedsAndCountsWhenGateInactive() {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();

        boolean admitted = gate.tryAdmitMutation();

        assertThat(admitted).isTrue();
        assertThat(gate.activeMutationCount()).isEqualTo(1);
    }

    @Test
    void tryAdmitMutation_failsAndNeverCountsWhenGateActive() {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.begin();

        boolean admitted = gate.tryAdmitMutation();

        assertThat(admitted).isFalse();
        assertThat(gate.activeMutationCount()).isZero();
    }

    @Test
    void releaseMutation_decrementsCount() {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.tryAdmitMutation();
        gate.tryAdmitMutation();

        gate.releaseMutation();

        assertThat(gate.activeMutationCount()).isEqualTo(1);
    }

    @Test
    void awaitDrain_returnsTrueImmediatelyWhenNoActiveMutations() throws InterruptedException {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.begin();

        boolean drained = gate.awaitDrain(Duration.ofMillis(50));

        assertThat(drained).isTrue();
    }

    @Test
    void awaitDrain_waitsForInFlightMutationToRelease() throws Exception {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        assertThat(gate.tryAdmitMutation()).isTrue();
        gate.begin();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch releasedLatch = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    gate.releaseMutation();
                    releasedLatch.countDown();
                }
            });

            boolean drained = gate.awaitDrain(Duration.ofSeconds(5));

            assertThat(drained).isTrue();
            assertThat(releasedLatch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.activeMutationCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void awaitDrain_returnsFalseWhenTimeoutExpiresWithMutationStillActive() throws InterruptedException {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.tryAdmitMutation();
        gate.begin();

        boolean drained = gate.awaitDrain(Duration.ofMillis(50));

        assertThat(drained).isFalse();
        assertThat(gate.activeMutationCount()).isEqualTo(1);
    }

    /**
     * Reproduit la fenetre de course identifiee par le troisieme audit
     * externe (constat M-01) : {@code tryAdmitMutation()} et {@code begin()}
     * doivent etre mutuellement exclusifs, sinon une requete pourrait etre
     * admise "juste apres" begin() sans jamais etre comptee dans le
     * drainage.
     */
    @Test
    void tryAdmitMutation_neverSucceedsAfterBeginEvenUnderConcurrentAttempts() throws Exception {
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.begin();

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                results.add(executor.submit(gate::tryAdmitMutation));
            }
            for (java.util.concurrent.Future<Boolean> result : results) {
                assertThat(result.get()).isFalse();
            }
            assertThat(gate.activeMutationCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }
}
