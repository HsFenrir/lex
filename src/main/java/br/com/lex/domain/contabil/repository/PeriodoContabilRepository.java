package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.PeriodoContabil;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PeriodoContabilRepository
        extends JpaRepository<PeriodoContabil, UUID> {

    Optional<PeriodoContabil> findByIdAndEmpresaIdAndExercicioId(
            UUID id,
            UUID empresaId,
            UUID exercicioId
    );
}