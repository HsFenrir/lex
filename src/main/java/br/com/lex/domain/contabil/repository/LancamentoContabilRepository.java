
package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LancamentoContabilRepository
        extends JpaRepository<LancamentoContabil, UUID> {

    Optional<LancamentoContabil> findByIdAndEmpresaId(
            UUID id,
            UUID empresaId
    );

    List<LancamentoContabil>
    findAllByEmpresaIdAndExercicioIdOrderByDataAscNumeroAsc(
            UUID empresaId,
            UUID exercicioId
    );

    List<LancamentoContabil>
    findAllByEmpresaIdAndExercicioIdAndPeriodoIdOrderByDataAscNumeroAsc(
            UUID empresaId,
            UUID exercicioId,
            UUID periodoId
    );

    Optional<LancamentoContabil>
    findByIdAndEmpresaIdAndStatus(
            UUID id,
            UUID empresaId,
            StatusLancamento status
    );

    boolean existsByEmpresaIdAndExercicioIdAndNumero(
            UUID empresaId,
            UUID exercicioId,
            Long numero
    );

    boolean existsByEmpresaIdAndExercicioIdAndOrigemAndStatusIn(
            UUID empresaId, UUID exercicioId, OrigemLancamento origem, Collection<StatusLancamento> status
    );

    boolean existsByEmpresaIdAndExercicioIdAndStatusIn(
            UUID empresaId, UUID exercicioId, Collection<StatusLancamento> status
    );

    boolean existsByEmpresaIdAndExercicioIdAndStatus(UUID empresaId, UUID exercicioId, StatusLancamento status);

    boolean existsByEmpresaIdAndExercicioIdAndPeriodoIdAndStatus(
            UUID empresaId, UUID exercicioId, UUID periodoId, StatusLancamento status
    );

    @Query("""
            select min(l.data) from LancamentoContabil l
            where l.empresaId = :empresaId and l.exercicioId = :exercicioId
              and l.origem = br.com.lex.domain.contabil.enums.OrigemLancamento.ABERTURA
              and l.exercicio.exercicioOrigemId is null
              and l.status in (br.com.lex.domain.contabil.enums.StatusLancamento.CONFIRMADO,
                               br.com.lex.domain.contabil.enums.StatusLancamento.ESTORNADO)
            """)
    Optional<LocalDate> buscarDataAberturaMigracao(
            @Param("empresaId") UUID empresaId, @Param("exercicioId") UUID exercicioId
    );

    Optional<LancamentoContabil>
    findByEstornoDeIdAndEmpresaId(
            UUID estornoDeId,
            UUID empresaId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT l
    FROM LancamentoContabil l
    WHERE l.id = :id
      AND l.empresaId = :empresaId
""")
    Optional<LancamentoContabil> buscarParaAlteracao(
            @Param("id") UUID id,
            @Param("empresaId") UUID empresaId
    );
}
