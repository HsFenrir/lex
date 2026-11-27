
package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.PartidaContabil;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartidaContabilRepository
        extends JpaRepository<PartidaContabil, UUID> {

    Optional<PartidaContabil> findByIdAndLancamentoId(UUID id, UUID lancamentoId);

    List<PartidaContabil> findAllByLancamentoIdOrderByIdAsc(
            UUID lancamentoId
    );

    List<DadosPartida> findAllProjetadasByLancamentoIdOrderByIdAsc(UUID lancamentoId);

    String FILTRO_ESCRITURACAO = """
            where l.empresaId = :empresaId and l.exercicioId = :exercicioId
              and l.data between :inicio and :fim
              and l.status in (br.com.lex.domain.contabil.enums.StatusLancamento.CONFIRMADO,
                               br.com.lex.domain.contabil.enums.StatusLancamento.ESTORNADO)
            """;

    String MOVIMENTOS_ESCRITURADOS = """
            select l.id as lancamentoId, l.periodoId as periodoId, l.data as data, l.numero as numero,
                   l.historico as historicoLancamento, l.origem as origem, l.status as status,
                   original.id as originalId, original.exercicioId as exercicioOriginalId,
                   original.numero as numeroOriginal,
                   p.id as partidaId, c.id as contaId, c.codigo as codigoConta, c.nome as nomeConta,
                   p.tipo as tipo, p.valor as valor, p.historico as historicoPartida
            from PartidaContabil p
            join p.lancamento l
            join p.contaContabil c
            left join l.estornoDe original
            """ + FILTRO_ESCRITURACAO;

    String ORDEM_ESCRITURACAO = " order by l.data, l.numero, l.id, p.id";

    @Query(MOVIMENTOS_ESCRITURADOS + ORDEM_ESCRITURACAO)
    List<MovimentoEscriturado> buscarMovimentosEscriturados(
            @Param("empresaId") UUID empresaId,
            @Param("exercicioId") UUID exercicioId,
            @Param("inicio") LocalDate inicio,
            @Param("fim") LocalDate fim
    );

    @Query(MOVIMENTOS_ESCRITURADOS + " and p.contaContabilId = :contaId" + ORDEM_ESCRITURACAO)
    List<MovimentoEscriturado> buscarMovimentosEscriturados(
            @Param("empresaId") UUID empresaId,
            @Param("exercicioId") UUID exercicioId,
            @Param("inicio") LocalDate inicio,
            @Param("fim") LocalDate fim,
            @Param("contaId") UUID contaId
    );

    interface MovimentoEscriturado {
        UUID getLancamentoId();
        UUID getPeriodoId();
        LocalDate getData();
        Long getNumero();
        String getHistoricoLancamento();
        OrigemLancamento getOrigem();
        StatusLancamento getStatus();
        UUID getOriginalId();
        UUID getExercicioOriginalId();
        Long getNumeroOriginal();
        UUID getPartidaId();
        UUID getContaId();
        String getCodigoConta();
        String getNomeConta();
        TipoPartida getTipo();
        BigDecimal getValor();
        String getHistoricoPartida();
    }

    @Query("""
            select p.contaContabilId as contaId, p.tipo as tipo,
                   sum(case when l.data < :corte then p.valor else 0 end) as totalAnterior,
                   sum(case when l.data >= :corte then p.valor else 0 end) as totalPeriodo
            from PartidaContabil p join p.lancamento l
            """ + FILTRO_ESCRITURACAO + " group by p.contaContabilId, p.tipo")
    List<TotaisEscriturados> somarMovimentosEscriturados(
            @Param("empresaId") UUID empresaId,
            @Param("exercicioId") UUID exercicioId,
            @Param("inicio") LocalDate inicio,
            @Param("fim") LocalDate fim,
            @Param("corte") LocalDate corte
    );

    interface TotaisEscriturados {
        UUID getContaId();
        TipoPartida getTipo();
        BigDecimal getTotalAnterior();
        BigDecimal getTotalPeriodo();
    }

    @Query("""
            select p.contaContabilId as contaId, p.tipo as tipo, l.origem as origem,
                   original.origem as origemOriginal,
                   sum(case when l.data < :corte then p.valor else 0 end) as totalAnterior,
                   sum(case when l.data >= :corte then p.valor else 0 end) as totalPeriodo
            from PartidaContabil p join p.lancamento l
            join p.contaContabil c left join l.estornoDe original
            """ + FILTRO_ESCRITURACAO + """
              and l.origem <> br.com.lex.domain.contabil.enums.OrigemLancamento.ENCERRAMENTO
              and (original.origem is null or original.origem <> br.com.lex.domain.contabil.enums.OrigemLancamento.ENCERRAMENTO)
              and c.grupo in (br.com.lex.domain.contabil.enums.GrupoConta.RECEITA,
                              br.com.lex.domain.contabil.enums.GrupoConta.CUSTO,
                              br.com.lex.domain.contabil.enums.GrupoConta.DESPESA)
            group by p.contaContabilId, p.tipo, l.origem, original.origem
            """)
    List<TotaisPorOrigem> somarMovimentosDeResultadoPorOrigem(
            @Param("empresaId") UUID empresaId,
            @Param("exercicioId") UUID exercicioId,
            @Param("inicio") LocalDate inicio,
            @Param("fim") LocalDate fim,
            @Param("corte") LocalDate corte
    );

    @Query("""
            select min(l.data)
            from PartidaContabil p join p.lancamento l join p.contaContabil c
            """ + FILTRO_ESCRITURACAO + """
              and l.origem = br.com.lex.domain.contabil.enums.OrigemLancamento.ABERTURA
              and c.grupo in (br.com.lex.domain.contabil.enums.GrupoConta.RECEITA,
                              br.com.lex.domain.contabil.enums.GrupoConta.CUSTO,
                              br.com.lex.domain.contabil.enums.GrupoConta.DESPESA)
            """)
    Optional<LocalDate> buscarDataAberturaComResultado(
            @Param("empresaId") UUID empresaId,
            @Param("exercicioId") UUID exercicioId,
            @Param("inicio") LocalDate inicio,
            @Param("fim") LocalDate fim
    );

    interface TotaisPorOrigem extends TotaisEscriturados {
        OrigemLancamento getOrigem();
        OrigemLancamento getOrigemOriginal();
    }

    interface DadosPartida {
        UUID getContaContabilId();
        TipoPartida getTipo();
        BigDecimal getValor();
        String getHistorico();
    }

    List<PartidaContabil>
    findAllByLancamentoIdAndPlanoContasIdOrderByIdAsc(
            UUID lancamentoId,
            UUID planoContasId
    );

    boolean existsByLancamentoId(UUID lancamentoId);

    long countByLancamentoId(UUID lancamentoId);
}
