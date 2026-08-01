package dev.subnetory.repository;

import dev.subnetory.domain.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    @Query("select c from MfaRecoveryCode c where c.userId = :userId and c.usedAt is null")
    List<MfaRecoveryCode> findUnusedByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MfaRecoveryCode c where c.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
