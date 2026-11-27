package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.enums.ClassificacaoBP;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalancoPatrimonial(
        Balancete.Empresa empresa, Balancete.Exercicio exercicio, LocalDate dataBase, List<Secao> secoes,
        BigDecimal ativo, BigDecimal passivo, BigDecimal patrimonioLiquidoEscriturado,
        BigDecimal resultadoNaoEncerrado, BigDecimal patrimonioLiquido,
        BigDecimal diferencaPatrimonial, boolean equilibrado
) {
    public BalancoPatrimonial {
        secoes = List.copyOf(secoes);
    }

    public record Secao(ClassificacaoBP classificacao, List<Linha> linhas, BigDecimal total) {
        public Secao {
            linhas = List.copyOf(linhas);
        }
    }

    public record Linha(Balancete.Conta conta, BigDecimal saldoContabil, BigDecimal valorApresentado) {
    }
}
