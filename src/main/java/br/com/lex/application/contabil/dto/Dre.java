package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.enums.ClassificacaoDRE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record Dre(
        Balancete.Empresa empresa, Balancete.Exercicio exercicio, LocalDate inicio, LocalDate fim,
        List<Linha> linhas, List<ContaResultado> contas, LocalDate dataAberturaComResultado,
        boolean historicoAnteriorAMigracaoSemDetalhamento, boolean incluiAberturaNoResultadoDoIntervalo
) {
    public Dre {
        linhas = List.copyOf(linhas);
        contas = List.copyOf(contas);
    }

    public enum Rubrica {
        RECEITA_BRUTA, DEDUCOES_RECEITA, RECEITA_LIQUIDA, CUSTOS, RESULTADO_BRUTO,
        DESPESAS_OPERACIONAIS, RESULTADO_OPERACIONAL, RESULTADO_FINANCEIRO, OUTRAS_RECEITAS, OUTRAS_DESPESAS,
        RESULTADO_ANTES_DOS_TRIBUTOS, TRIBUTOS_SOBRE_RESULTADO, RESULTADO_LIQUIDO
    }

    public record Linha(Rubrica rubrica, Valores valores, BigDecimal valorDemonstrado) {
    }

    public record ContaResultado(Balancete.Conta conta, ClassificacaoDRE classificacao, Valores valores) {
    }

    public record Valores(
            BigDecimal movimentosEconomicos, BigDecimal aberturaImportada, BigDecimal estornosDeAbertura,
            BigDecimal acumuladoAteFim
    ) {
        public Valores somar(Valores outro) {
            return new Valores(movimentosEconomicos.add(outro.movimentosEconomicos),
                    aberturaImportada.add(outro.aberturaImportada),
                    estornosDeAbertura.add(outro.estornosDeAbertura),
                    acumuladoAteFim.add(outro.acumuladoAteFim));
        }

        public BigDecimal efeitoEscrituradoNoIntervalo() {
            return movimentosEconomicos.add(aberturaImportada).add(estornosDeAbertura);
        }
    }
}
