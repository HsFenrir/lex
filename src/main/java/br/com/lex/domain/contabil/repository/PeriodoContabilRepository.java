package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.enums.StatusPeriodo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PeriodoContabilRepository
        extends JpaRepository<PeriodoContabil, UUID> {

    List<PeriodoContabil> findAllByEmpresaIdAndExercicioIdOrderByMes(UUID empresaId, UUID exercicioId);

    boolean existsByEmpresaIdAndExercicioIdAndMesLessThanAndStatus(
            UUID empresaId, UUID exercicioId, Short mes, StatusPeriodo status
    );

    Optional<PeriodoContabil> findByIdAndEmpresaIdAndExercicioId(
            UUID id,
            UUID empresaId,
            UUID exercicioId
    );
}
