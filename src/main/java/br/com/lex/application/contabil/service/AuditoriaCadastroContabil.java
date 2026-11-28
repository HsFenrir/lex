package br.com.lex.application.contabil.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Component
@RequiredArgsConstructor
class AuditoriaCadastroContabil {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    void registrar(UUID empresaId, UUID usuarioId, String entidade, UUID id, String acao,
                   Object anterior, Object novo) {
        jdbc.update("""
                insert into auditoria_evento
                    (empresa_id, usuario_id, entidade, entidade_id, acao, dados_anteriores, dados_novos)
                values (?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, empresaId, usuarioId, entidade, id, acao,
                anterior == null ? null : json.writeValueAsString(anterior), json.writeValueAsString(novo));
    }
}
