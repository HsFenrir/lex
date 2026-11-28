package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

public record ApropriacaoDepreciacao(
        UUID id, UUID ativoId, UUID exercicioId, UUID periodoId, YearMonth competencia,
        BigDecimal valor, UUID lancamentoId, Long numeroContabil, ReversaoOperacional reversao
) {
    public ApropriacaoDepreciacao(UUID id, UUID ativoId, UUID exercicioId, UUID periodoId, YearMonth competencia,
                                   BigDecimal valor, UUID lancamentoId, Long numeroContabil) {
        this(id, ativoId, exercicioId, periodoId, competencia, valor, lancamentoId, numeroContabil, null);
    }
}
