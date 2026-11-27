package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.ApuracaoSaldos.Resumo;
import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.ClassificacaoDRE;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.TipoConta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record Balancete(
        Empresa empresa, Exercicio exercicio, LocalDate inicio, LocalDate fim,
        List<Linha> linhas, Totais totais, Equilibrio equilibrio
) {
    public Balancete {
        linhas = List.copyOf(linhas);
    }

    public record Empresa(UUID id, String razaoSocial, String cnpj) {
    }

    public record Exercicio(UUID id, short ano, LocalDate dataInicio, LocalDate dataFim) {
    }

    public record Conta(
            UUID id, UUID contaPaiId, String codigo, String nome, TipoConta tipo, NaturezaConta natureza,
            boolean ativa, GrupoConta grupo, ClassificacaoBP classificacaoBP, ClassificacaoDRE classificacaoDRE,
            boolean contaRedutora, UUID contaReduzidaId
    ) {
    }

    public record Linha(Conta conta, Resumo saldos) {
    }

    public record Totais(
            BigDecimal saldoAnteriorDevedor, BigDecimal saldoAnteriorCredor,
            BigDecimal debitos, BigDecimal creditos, BigDecimal saldoFinalDevedor, BigDecimal saldoFinalCredor
    ) {
        public Equilibrio verificarEquilibrio() {
            return new Equilibrio(saldoAnteriorDevedor.compareTo(saldoAnteriorCredor) == 0,
                    debitos.compareTo(creditos) == 0, saldoFinalDevedor.compareTo(saldoFinalCredor) == 0);
        }
    }

    public record Equilibrio(boolean saldoAnterior, boolean movimentos, boolean saldoFinal) {
    }
}
