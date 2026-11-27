package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FichaAtivo(
        UUID id, UUID empresaId, UUID usuarioId, String codigo, String descricao, UUID contaPatrimonialId,
        LocalDate dataAquisicao, LocalDate dataDisponibilidade, BigDecimal custoAquisicao, boolean depreciavel,
        BigDecimal valorResidual, Integer vidaUtilMeses, BigDecimal baseDepreciavel, UUID contaRedutoraId,
        UUID contaDespesaId, Situacao situacao, UUID lancamentoId, UUID exercicioAquisicaoId, long numeroContabil
) {
    public enum Situacao {
        AGUARDANDO_DISPONIBILIDADE, DISPONIBILIDADE_REGISTRADA
    }
}
