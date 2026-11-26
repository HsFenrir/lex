package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.enums.TipoPartida;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AberturaContabil(
        UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId,
        LocalDate data, String historico, List<SaldoPorConta> saldos
) {
    public AberturaContabil {
        if (saldos == null || saldos.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Informe a coleção de saldos sem itens nulos.");
        }
        saldos = List.copyOf(saldos);
    }

    public record SaldoPorConta(UUID contaId, TipoPartida tipo, BigDecimal valor) {
    }
}
