package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.enums.OrigemLancamento;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AberturaConfirmada(
        UUID lancamentoId, UUID empresaId, UUID exercicioId, UUID periodoId,
        long numero, LocalDate data, OrigemLancamento origem, UUID confirmadoPor, OffsetDateTime confirmadoEm
) {
}
