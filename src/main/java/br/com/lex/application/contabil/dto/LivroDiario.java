package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LivroDiario(
        Empresa empresa, Exercicio exercicio, LocalDate inicio, LocalDate fim,
        List<Lancamento> lancamentos, BigDecimal totalDebitos, BigDecimal totalCreditos
) {
    public LivroDiario {
        lancamentos = List.copyOf(lancamentos);
    }

    public record Empresa(UUID id, String razaoSocial, String cnpj) {
    }

    public record Exercicio(UUID id, short ano, LocalDate dataInicio, LocalDate dataFim) {
    }

    public record ReferenciaLancamento(UUID id, UUID exercicioId, long numero) {
    }

    public record Lancamento(
            ReferenciaLancamento referencia, UUID periodoId, LocalDate data, String historico,
            OrigemLancamento origem, StatusLancamento status, ReferenciaLancamento original,
            List<Partida> partidas, BigDecimal totalDebitos, BigDecimal totalCreditos
    ) {
        public Lancamento {
            partidas = List.copyOf(partidas);
        }
    }

    public record Partida(
            UUID id, UUID contaId, String codigoConta, String nomeConta,
            TipoPartida tipo, BigDecimal valor, String historico
    ) {
    }
}
