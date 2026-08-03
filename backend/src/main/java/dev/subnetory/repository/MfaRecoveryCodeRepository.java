package dev.subnetory.repository;

import dev.subnetory.domain.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Long> {

    @Query("select c from MfaRecoveryCode c where c.userId = :userId and c.usedAt is null")
    List<MfaRecoveryCode> findUnusedByUserId(@Param("userId") Long userId);

    /**
     * Consommation atomique d'un code de recuperation (audit 03/08/2026,
     * correctif MOYEN) : {@code MfaService#verifyAndConsumeRecoveryCode}
     * lisait la liste des codes non utilises puis appelait {@code save()}
     * sur celui trouve, sans aucun verrou ni contrainte empechant deux
     * authentifications concurrentes de lire toutes les deux le meme code
     * avec {@code usedAt = null} avant que l'une ou l'autre n'ecrive —
     * consommant ainsi deux fois un code cense etre a usage unique. La
     * clause {@code and usedAt is null} rend cette mise a jour atomique au
     * niveau de la ligne (PostgreSQL) : un seul appelant concurrent peut
     * reussir a passer {@code usedAt} de {@code null} a une valeur, l'autre
     * obtient {@code 0} ligne affectee et doit traiter le code comme
     * invalide.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MfaRecoveryCode c set c.usedAt = :usedAt where c.id = :id and c.usedAt is null")
    int markUsedIfUnused(@Param("id") Long id, @Param("usedAt") OffsetDateTime usedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MfaRecoveryCode c where c.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
