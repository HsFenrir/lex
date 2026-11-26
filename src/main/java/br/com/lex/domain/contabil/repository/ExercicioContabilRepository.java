package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.ExercicioContabil;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExercicioContabilRepository
        extends JpaRepository<ExercicioContabil, UUID> {

    boolean existsByEmpresaIdAndAnoLessThan(UUID empresaId, Short ano);

    Optional<ExercicioContabil> findByIdAndEmpresaId(
            UUID id,
            UUID empresaId
    );
}
