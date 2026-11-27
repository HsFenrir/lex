package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.AtivoPatrimonial;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AtivoPatrimonialRepository extends JpaRepository<AtivoPatrimonial, UUID> {

    @EntityGraph(attributePaths = "lancamentoAquisicao")
    Optional<AtivoPatrimonial> findByIdAndEmpresaId(UUID id, UUID empresaId);

    @EntityGraph(attributePaths = "lancamentoAquisicao")
    List<AtivoPatrimonial> findAllByEmpresaIdOrderByCodigoAsc(UUID empresaId);

    @Query("select a.id from AtivoPatrimonial a where a.empresaId = :empresaId order by a.id")
    List<UUID> listarIdsParaProcessamento(UUID empresaId);
}
