package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AquisicaoAtivo(
        UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId,
        String codigo, String descricao, UUID contaPatrimonialId, UUID contrapartidaId,
        LocalDate dataAquisicao, LocalDate dataDisponibilidade, BigDecimal custoAquisicao,
        boolean depreciavel, BigDecimal valorResidual, Integer vidaUtilMeses, UUID contaDespesaId, String historico
) {
}
