package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.ContaContabil;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContaContabilRepository
        extends JpaRepository<ContaContabil, UUID> {

    Optional<ContaContabil> findByIdAndPlanoContasId(UUID id, UUID planoContasId);

    List<ContaContabil> findAllByPlanoContasIdOrderByCodigoAsc(
            UUID planoContasId
    );

    Optional<ContaContabil> findByPlanoContasIdAndCodigo(
            UUID planoContasId,
            String codigo
    );

    boolean existsByPlanoContasIdAndModeloContaId(
            UUID planoContasId,
            UUID modeloContaId
    );
}
