package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Valor de custo da entrada na compra ou custo PEPS apropriado na venda. */
public record OperacaoEstoqueConfirmada(
        UUID movimentoId, UUID lancamentoId, long numeroContabil, BigDecimal quantidade,
        BigDecimal valorComercial, BigDecimal custoEstoque
) {
}
