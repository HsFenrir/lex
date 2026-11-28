package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.AtivoPatrimonial;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AtivoPatrimonialRepository extends JpaRepository<AtivoPatrimonial, UUID> {

    @EntityGraph(attributePaths = {"lancamentoAquisicao", "lancamentoAbertura"})
    Optional<AtivoPatrimonial> findByIdAndEmpresaId(UUID id, UUID empresaId);

    @EntityGraph(attributePaths = {"lancamentoAquisicao", "lancamentoAbertura"})
    List<AtivoPatrimonial> findAllByEmpresaIdOrderByCodigoAsc(UUID empresaId);

    @Query("select a.id from AtivoPatrimonial a where a.empresaId = :empresaId order by a.id")
    List<UUID> listarIdsParaProcessamento(UUID empresaId);

    boolean existsByEmpresaIdAndLancamentoAberturaId(UUID empresaId, UUID lancamentoAberturaId);

    boolean existsByEmpresaIdAndCodigoIn(UUID empresaId, Collection<String> codigos);

    @Query("""
            select a.contaPatrimonialId as contaId, sum(a.custoAquisicao) as valor,
                   sum(case when abertura.id is not null then a.custoAquisicao else 0 end) as importado
            from AtivoPatrimonial a left join a.lancamentoAbertura abertura
            where a.empresaId = :empresaId and coalesce(abertura.data, a.dataAquisicao) <= :dataBase
            group by a.contaPatrimonialId
            """)
    List<CustoPorConta> somarCustosAte(UUID empresaId, LocalDate dataBase);

    interface CustoPorConta {
        UUID getContaId();
        BigDecimal getValor();
        BigDecimal getImportado();
    }

    @Query("""
            select a.contaPatrimonialId as contaPatrimonialId, a.contaRedutoraId as contaRedutoraId,
                   sum(a.custoAquisicao) as custo, sum(a.depreciacaoInicial) as depreciacao
            from AtivoPatrimonial a
            where a.empresaId = :empresaId and a.lancamentoAbertura.id = :aberturaId
            group by a.contaPatrimonialId, a.contaRedutoraId
            """)
    List<ValoresImportados> somarImportados(UUID empresaId, UUID aberturaId);

    interface ValoresImportados {
        UUID getContaPatrimonialId();
        UUID getContaRedutoraId();
        BigDecimal getCusto();
        BigDecimal getDepreciacao();
    }
}
