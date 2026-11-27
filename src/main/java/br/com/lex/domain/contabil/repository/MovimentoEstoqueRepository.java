package br.com.lex.domain.contabil.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MovimentoEstoqueRepository {

    private final JdbcTemplate jdbc;

    public LocalDate ultimaData(UUID empresaId, UUID produtoId) {
        return jdbc.queryForObject("""
                select max(data) from movimento_estoque where empresa_id = ? and produto_id = ?
                """, LocalDate.class, empresaId, produtoId);
    }

    public void inserir(Movimento m) {
        jdbc.update("""
                insert into movimento_estoque
                  (id, empresa_id, produto_id, exercicio_id, periodo_id, usuario_id, data, tipo, quantidade, valor_total)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, m.id(), m.empresaId(), m.produtoId(), m.exercicioId(), m.periodoId(), m.usuarioId(),
                m.data(), m.tipo().name(), m.quantidade(), m.valorTotal());
    }

    public void inserirLote(Movimento entrada, BigDecimal custoUnitario) {
        jdbc.update("""
                insert into lote_estoque (entrada_id, empresa_id, produto_id, custo_unitario, quantidade_restante, valor_restante)
                values (?, ?, ?, ?, ?, ?)
                """, entrada.id(), entrada.empresaId(), entrada.produtoId(), custoUnitario,
                entrada.quantidade(), entrada.valorTotal());
    }

    public List<Lote> lotes(UUID empresaId, UUID produtoId, boolean incluirEsgotados) {
        return jdbc.query("""
                select l.entrada_id, e.data, e.quantidade, e.valor_total,
                       l.custo_unitario, l.quantidade_restante, l.valor_restante
                from lote_estoque l join movimento_estoque e on e.id = l.entrada_id
                where l.empresa_id = ? and l.produto_id = ? and (? or l.quantidade_restante > 0)
                order by e.data, e.ordem
                """, (rs, linha) -> new Lote(uuid(rs, "entrada_id"), rs.getObject("data", LocalDate.class),
                rs.getBigDecimal("quantidade"), rs.getBigDecimal("custo_unitario"),
                rs.getBigDecimal("valor_total"), rs.getBigDecimal("quantidade_restante"),
                rs.getBigDecimal("valor_restante")), empresaId, produtoId, incluirEsgotados);
    }

    public void consumir(Movimento saida, Alocacao alocacao) {
        int atualizadas = jdbc.update("""
                update lote_estoque
                   set quantidade_restante = quantidade_restante - ?, valor_restante = valor_restante - ?
                 where empresa_id = ? and produto_id = ? and entrada_id = ?
                   and quantidade_restante >= ? and valor_restante >= ?
                """, alocacao.quantidade(), alocacao.valor(), saida.empresaId(), saida.produtoId(), alocacao.entradaId(),
                alocacao.quantidade(), alocacao.valor());
        if (atualizadas != 1) {
            throw new IllegalStateException("Lote indisponível para o consumo informado.");
        }
        jdbc.update("""
                insert into alocacao_estoque (saida_id, entrada_id, empresa_id, produto_id, quantidade, valor)
                values (?, ?, ?, ?, ?, ?)
                """, saida.id(), alocacao.entradaId(), saida.empresaId(), saida.produtoId(),
                alocacao.quantidade(), alocacao.valor());
    }

    public boolean vincularLancamento(UUID empresaId, UUID movimentoId, UUID lancamentoId) {
        return jdbc.update("""
                update movimento_estoque set lancamento_id = ?
                where empresa_id = ? and id = ? and lancamento_id is null
                """, lancamentoId, empresaId, movimentoId) == 1;
    }

    public Saldo saldo(UUID empresaId, UUID produtoId) {
        return jdbc.queryForObject("""
                select coalesce(sum(quantidade_restante), 0) as quantidade,
                       coalesce(sum(valor_restante), 0) as valor
                from lote_estoque where empresa_id = ? and produto_id = ?
                """, (rs, linha) -> new Saldo(rs.getBigDecimal("quantidade"), rs.getBigDecimal("valor")),
                empresaId, produtoId);
    }

    public List<Movimento> historico(UUID empresaId, UUID produtoId) {
        return jdbc.query("""
                select id, empresa_id, produto_id, exercicio_id, periodo_id, usuario_id, data, tipo,
                       quantidade, valor_total, lancamento_id
                from movimento_estoque where empresa_id = ? and produto_id = ?
                order by data, ordem
                """, (rs, linha) -> new Movimento(uuid(rs, "id"), uuid(rs, "empresa_id"), uuid(rs, "produto_id"),
                uuid(rs, "exercicio_id"), uuid(rs, "periodo_id"), uuid(rs, "usuario_id"),
                rs.getObject("data", LocalDate.class), Tipo.valueOf(rs.getString("tipo")),
                rs.getBigDecimal("quantidade"), rs.getBigDecimal("valor_total"), uuid(rs, "lancamento_id")),
                empresaId, produtoId);
    }

    public List<Alocacao> alocacoes(UUID empresaId, UUID produtoId, UUID saidaId) {
        return jdbc.query("""
                select a.entrada_id, a.quantidade, a.valor
                from alocacao_estoque a join movimento_estoque e on e.id = a.entrada_id
                where a.empresa_id = ? and a.produto_id = ? and a.saida_id = ?
                order by e.data, e.ordem
                """, (rs, linha) -> new Alocacao(uuid(rs, "entrada_id"), rs.getBigDecimal("quantidade"),
                rs.getBigDecimal("valor")), empresaId, produtoId, saidaId);
    }

    private UUID uuid(ResultSet rs, String coluna) throws SQLException {
        return rs.getObject(coluna, UUID.class);
    }

    public enum Tipo { ENTRADA, SAIDA }

    public record Movimento(UUID id, UUID empresaId, UUID produtoId, UUID exercicioId, UUID periodoId, UUID usuarioId,
                            LocalDate data, Tipo tipo, BigDecimal quantidade, BigDecimal valorTotal, UUID lancamentoId) {
    }

    public record Lote(UUID entradaId, LocalDate data, BigDecimal quantidadeOriginal, BigDecimal custoUnitario,
                       BigDecimal valorOriginal,
                       BigDecimal quantidadeRestante, BigDecimal valorRestante) {
    }

    public record Alocacao(UUID entradaId, BigDecimal quantidade, BigDecimal valor) {
    }

    public record Saldo(BigDecimal quantidade, BigDecimal valor) {
    }
}
