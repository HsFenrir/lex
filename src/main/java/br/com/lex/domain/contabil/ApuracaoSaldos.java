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
            anterior = anterior.add(tipo == TipoPartida.DEBITO ? valor : valor.negate());
        } else if (tipo == TipoPartida.DEBITO) {
            debitos = debitos.add(valor);
        } else {
            creditos = creditos.add(valor);
        }
    }

    public Saldo saldoAtual() {
        return new Saldo(anterior.add(debitos).subtract(creditos));
    }

    public Resumo resumo() {
        return new Resumo(new Saldo(anterior), debitos, creditos, saldoAtual());
    }

    public record Resumo(Saldo saldoAnterior, BigDecimal totalDebitos, BigDecimal totalCreditos, Saldo saldoFinal) {
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
