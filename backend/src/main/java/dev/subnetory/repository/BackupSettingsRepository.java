package dev.subnetory.repository;

import dev.subnetory.domain.BackupSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BackupSettingsRepository extends JpaRepository<BackupSettings, Long> {
}
