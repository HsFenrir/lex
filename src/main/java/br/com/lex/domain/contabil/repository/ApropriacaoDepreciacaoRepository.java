package br.com.lex.domain.contabil.repository;

import br.com.lex.application.contabil.dto.ApropriacaoDepreciacao;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ApropriacaoDepreciacaoRepository {

    private final JdbcTemplate jdbc;

    public List<ApropriacaoDepreciacao> listar(UUID empresaId, UUID ativoId) {
        return consultar("and d.ativo_id = ?", empresaId, ativoId);
    }

    public List<ApropriacaoDepreciacao> listarDaEmpresa(UUID empresaId) {
        return consultar("", empresaId);
    }

    private List<ApropriacaoDepreciacao> consultar(String filtro, Object... parametros) {
        return jdbc.query("""
                select d.id, d.ativo_id, d.exercicio_id, d.periodo_id, d.competencia, d.valor, d.lancamento_id, l.numero
                from apropriacao_depreciacao d
                left join lancamento_contabil l on l.id = d.lancamento_id
                where d.empresa_id = ?
                """ + filtro + " order by d.competencia", (rs, linha) -> new ApropriacaoDepreciacao(
                rs.getObject("id", UUID.class), rs.getObject("ativo_id", UUID.class), rs.getObject("exercicio_id", UUID.class),
                rs.getObject("periodo_id", UUID.class), YearMonth.from(rs.getObject("competencia", LocalDate.class)),
                rs.getBigDecimal("valor"), rs.getObject("lancamento_id", UUID.class), rs.getObject("numero", Long.class)),
                parametros);
    }

    public void inserir(UUID empresaId, UUID usuarioId, ApropriacaoDepreciacao apropriacao) {
        jdbc.update("""
                insert into apropriacao_depreciacao
                    (id, empresa_id, ativo_id, exercicio_id, periodo_id, usuario_id, competencia, valor, lancamento_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, apropriacao.id(), empresaId, apropriacao.ativoId(), apropriacao.exercicioId(), apropriacao.periodoId(),
                usuarioId, apropriacao.competencia().atDay(1), apropriacao.valor(), apropriacao.lancamentoId());
    }

    public List<String> buscarPendenciasAte(UUID empresaId, LocalDate dataFinal) {
        return jdbc.queryForList("""
                select a.codigo || ' (' || to_char(
                    date_trunc('month', a.data_disponibilidade::timestamp) + h.meses * interval '1 month', 'YYYY-MM'
                ) || ')'
                from ativo_patrimonial a
                cross join lateral (
                    select count(*) as meses, coalesce(sum(d.valor), 0) as acumulado
                    from apropriacao_depreciacao d
                    where d.empresa_id = a.empresa_id and d.ativo_id = a.id and d.competencia <= ?
                ) h
                where a.empresa_id = ? and a.depreciavel and a.data_disponibilidade <= ?
                  and h.meses < a.vida_util_meses and h.acumulado < a.custo_aquisicao - a.valor_residual
                  and date_trunc('month', a.data_disponibilidade::timestamp)
                      + h.meses * interval '1 month' <= ?
                order by a.codigo
                """, String.class, dataFinal, empresaId, dataFinal, dataFinal);
    }
}
