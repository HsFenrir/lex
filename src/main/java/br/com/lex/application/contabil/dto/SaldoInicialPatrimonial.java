package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SaldoInicialPatrimonial(UUID empresaId, UUID usuarioId, UUID aberturaId, List<Bem> bens) {
    public SaldoInicialPatrimonial {
        bens = List.copyOf(bens);
    }

    /** A posição informada é a da abertura. Meses consumidos incluem o mês da abertura quando já apropriado na origem. */
    public record Bem(String codigo, String descricao, UUID contaPatrimonialId, UUID contaDespesaId,
                      LocalDate dataAquisicao, LocalDate dataDisponibilidade, BigDecimal custoHistorico,
                      boolean depreciavel, BigDecimal valorResidual, Integer vidaUtilMeses,
                      BigDecimal depreciacaoAcumulada, int mesesConsumidos, boolean mesAberturaJaApropriado) {
    }
}
