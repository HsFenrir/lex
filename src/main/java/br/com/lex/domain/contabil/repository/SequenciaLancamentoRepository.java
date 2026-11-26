package br.com.lex.domain.contabil.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SequenciaLancamentoRepository {

    private final JdbcTemplate jdbcTemplate;

    public SequenciaLancamentoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> alocarNumero(UUID empresaId, UUID exercicioId) {
        List<Long> numeros = jdbcTemplate.queryForList("""
                INSERT INTO sequencia_lancamento (empresa_id, exercicio_id, ultimo_numero)
                VALUES (?, ?, 1)
                ON CONFLICT (empresa_id, exercicio_id) DO UPDATE
                SET ultimo_numero = sequencia_lancamento.ultimo_numero + 1
                WHERE sequencia_lancamento.ultimo_numero < ?
                RETURNING ultimo_numero
                """, Long.class, empresaId, exercicioId, Long.MAX_VALUE);
        return numeros.isEmpty() ? Optional.empty() : Optional.of(numeros.getFirst());
    }
}
