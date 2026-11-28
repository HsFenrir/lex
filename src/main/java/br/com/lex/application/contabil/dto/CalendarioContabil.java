package br.com.lex.application.contabil.dto;

import br.com.lex.domain.contabil.enums.StatusExercicio;
import br.com.lex.domain.contabil.enums.StatusPeriodo;

import java.time.LocalDate;
import java.util.UUID;

public final class CalendarioContabil {

    private CalendarioContabil() {}

    public record Exercicio(UUID id, UUID empresaId, short ano, LocalDate inicio, LocalDate fim,
                           StatusExercicio status, UUID exercicioOrigemId) {}

    public record Competencia(UUID id, UUID exercicioId, short ano, short mes, LocalDate inicio,
                              LocalDate fim, StatusPeriodo status) {}
}
