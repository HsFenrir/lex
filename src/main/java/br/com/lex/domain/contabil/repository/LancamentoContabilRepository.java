
package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
