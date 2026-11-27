package br.com.lex.domain.contabil;

import br.com.lex.domain.contabil.enums.TipoPartida;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Acumula partidas escrituradas de uma conta, desde o início do exercício até o fim do recorte. */
public final class ApuracaoSaldos {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final LocalDate inicio;
    private BigDecimal anterior = ZERO;
    private BigDecimal debitos = ZERO;
    private BigDecimal creditos = ZERO;

    public ApuracaoSaldos(LocalDate inicio) {
        this.inicio = inicio;
    }

    public void registrar(LocalDate data, TipoPartida tipo, BigDecimal valor) {
        if (data.isBefore(inicio)) {
            registrarTotais(tipo, valor, ZERO);
        } else {
            registrarTotais(tipo, ZERO, valor);
        }
    }

    public void registrarTotais(TipoPartida tipo, BigDecimal totalAnterior, BigDecimal totalPeriodo) {
        switch (tipo) {
            case DEBITO -> {
                anterior = anterior.add(totalAnterior);
                debitos = debitos.add(totalPeriodo);
            }
            case CREDITO -> {
                anterior = anterior.subtract(totalAnterior);
                creditos = creditos.add(totalPeriodo);
            }
        }
    }

    public Saldo saldoAtual() {
        return new Saldo(anterior.add(debitos).subtract(creditos));
    }

    public Resumo resumo() {
        return new Resumo(new Saldo(anterior), debitos, creditos, saldoAtual());
    }

    public record Resumo(Saldo saldoAnterior, BigDecimal totalDebitos, BigDecimal totalCreditos, Saldo saldoFinal) {
        public Resumo somar(Resumo outro) {
            return new Resumo(new Saldo(saldoAnterior.valor().add(outro.saldoAnterior.valor())),
                    totalDebitos.add(outro.totalDebitos), totalCreditos.add(outro.totalCreditos),
                    new Saldo(saldoFinal.valor().add(outro.saldoFinal.valor())));
        }
    }

    public record Saldo(BigDecimal valor) {
        public NaturezaSaldo natureza() {
            return switch (valor.signum()) {
                case 1 -> NaturezaSaldo.DEVEDOR;
                case -1 -> NaturezaSaldo.CREDOR;
                default -> NaturezaSaldo.ZERADO;
            };
        }
    }

    public enum NaturezaSaldo {
        DEVEDOR, CREDOR, ZERADO
    }
}
