package dev.subnetory.backup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctif securite MOYENNE (audit 04/08/2026) : voir
 * {@link RestoreMaintenanceGate} pour le contexte complet.
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
}
