package br.com.lex.application.contabil.service;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

final class CalendarioTestFixture {

    private CalendarioTestFixture() {
    }

    static void preencherEncerradosAteNovembro(JdbcTemplate jdbc, UUID exercicioId, int primeiroMes) {
        // Meses sem movimento, entre o período já fechado pelo teste e dezembro.
        jdbc.update("""
                insert into periodo_contabil (exercicio_id, empresa_id, ano, mes, data_inicio, data_fim)
                select e.id, e.empresa_id, e.ano, m, make_date(e.ano,m,1),
                       (make_date(e.ano,m,1) + interval '1 month - 1 day')::date
                from exercicio_contabil e cross join generate_series(?,11) m where e.id = ?
                """, primeiroMes, exercicioId);
        jdbc.update("update periodo_contabil set status = 'ENCERRADO' where exercicio_id = ? and mes between ? and 11",
                exercicioId, primeiroMes);
    }
}
