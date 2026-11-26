package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.ApuracaoSaldos.Resumo;
import br.com.lex.domain.contabil.ApuracaoSaldos.Saldo;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LivroRazao(
        UUID empresaId, UUID exercicioId, LocalDate inicio, LocalDate fim,
        Conta conta, List<Movimento> movimentos, Resumo saldos
) {
    public LivroRazao {
        movimentos = List.copyOf(movimentos);
    }

    public record Conta(UUID id, String codigo, String nome, NaturezaConta natureza) {
    }

    public record Movimento(
            LocalDate data, UUID lancamentoId, long numeroLancamento, String historicoLancamento,
            OrigemLancamento origem, StatusLancamento status, UUID partidaId, String historicoPartida,
            TipoPartida tipo, BigDecimal valor, Saldo saldoAcumulado
    ) {
    }
}
