package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** O valor unitário é o custo na compra e o preço na venda. */
public record OperacaoEstoque(
        UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId, UUID produtoId,
        LocalDate data, BigDecimal quantidade, BigDecimal valorUnitario, String historico, UUID contrapartidaId
) {
}
