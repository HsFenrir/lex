package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

public record ApropriacaoDepreciacao(
        UUID id, UUID ativoId, UUID exercicioId, UUID periodoId, YearMonth competencia,
        BigDecimal valor, UUID lancamentoId, Long numeroContabil
) {
}
