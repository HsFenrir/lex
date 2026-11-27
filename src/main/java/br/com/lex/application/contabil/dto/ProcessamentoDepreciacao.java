package br.com.lex.application.contabil.dto;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public record ProcessamentoDepreciacao(
        YearMonth competencia, List<ApropriacaoDepreciacao> processados,
        List<AtivoNaoElegivel> naoElegiveis, List<ApropriacaoDepreciacao> jaProcessados, BigDecimal valorTotal
) {
    public ProcessamentoDepreciacao {
        processados = List.copyOf(processados);
        naoElegiveis = List.copyOf(naoElegiveis);
        jaProcessados = List.copyOf(jaProcessados);
    }

    public record AtivoNaoElegivel(UUID ativoId, String codigo, String motivo) {
    }
}
