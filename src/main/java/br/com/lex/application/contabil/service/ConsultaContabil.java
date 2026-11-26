package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class ConsultaContabil {

    private final UsuarioRepository usuarios;
    private final EmpresaRepository empresas;
    private final ExercicioContabilRepository exercicios;

    Contexto validar(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim) {
        if (empresaId == null || usuarioId == null || exercicioId == null || inicio == null || fim == null) {
            throw new IllegalArgumentException("Empresa, usuário, exercício e datas devem ser informados.");
        }

        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        if (!usuario.isAtivo()) {
            throw new IllegalStateException("O usuário deve estar ativo.");
        }

        Empresa empresa = empresas.findByIdAndUsuarioId(empresaId, usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa não encontrada ou não pertence ao usuário."));
        if (!empresa.isAtiva() || empresa.getArquivadaEm() != null) {
            throw new IllegalStateException("A empresa deve estar ativa e não arquivada.");
        }

        ExercicioContabil exercicio = exercicios.findByIdAndEmpresaId(exercicioId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Exercício não encontrado para a empresa."));
        if (inicio.isAfter(fim) || inicio.isBefore(exercicio.getDataInicio()) || fim.isAfter(exercicio.getDataFim())) {
            throw new IllegalArgumentException("O intervalo deve estar contido nas datas do exercício.");
        }

        return new Contexto(empresa, exercicio);
    }

    record Contexto(Empresa empresa, ExercicioContabil exercicio) {
    }
}
