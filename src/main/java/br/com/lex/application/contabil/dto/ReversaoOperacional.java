package br.com.lex.application.contabil.dto;

import java.time.LocalDate;
import java.util.UUID;

/** Para competência de valor zero, as referências contábeis são nulas. */
public record ReversaoOperacional(UUID operacaoOriginalId, UUID lancamentoOriginalId,
                                  UUID lancamentoEstornoId, Long numeroContabil,
                                  LocalDate data, UUID usuarioId) {
}
