package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SaldoInicialEstoque(UUID empresaId, UUID usuarioId, UUID aberturaId, List<Lote> lotes) {
    public SaldoInicialEstoque {
        lotes = List.copyOf(lotes);
    }

    /** Para o mesmo produto, informe os lotes na ordem PEPS do inventário de origem. */
    public record Lote(UUID produtoId, BigDecimal quantidade, BigDecimal custoUnitario) {
    }
}
