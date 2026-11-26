package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.TipoConta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ContaContabilRepository
        extends JpaRepository<ContaContabil, UUID> {

    Optional<ContaContabil> findByIdAndPlanoContasId(UUID id, UUID planoContasId);

    @Query("""
            select c.codigo as codigo, c.nome as nome, c.natureza as natureza, c.tipo as tipo
            from ContaContabil c join c.planoContas plano
            where c.id = :contaId and plano.empresaId = :empresaId
            """)
    Optional<DadosConta> buscarDadosDaConta(@Param("empresaId") UUID empresaId, @Param("contaId") UUID contaId);

    interface DadosConta {
        String getCodigo();
        String getNome();
        NaturezaConta getNatureza();
        TipoConta getTipo();
    }

    @Query(value = """
            SELECT id FROM conta_contabil
            WHERE plano_contas_id = :planoId AND id IN (:ids)
              AND ativa AND tipo = 'ANALITICA'
            ORDER BY id
            FOR SHARE
            """, nativeQuery = true)
    List<UUID> bloquearContasAptas(@Param("planoId") UUID planoId, @Param("ids") Set<UUID> ids);

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
