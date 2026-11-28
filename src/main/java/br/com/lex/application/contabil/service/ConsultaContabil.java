package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
    private final EntityManager em;

    Contexto validar(UUID empresaId, UUID usuarioId, UUID exercicioId, LocalDate inicio, LocalDate fim) {
        if (empresaId == null || usuarioId == null || exercicioId == null || inicio == null || fim == null) {
            throw new IllegalArgumentException("Empresa, usuário, exercício e datas devem ser informados.");
        }

        usuarioAtivo(usuarioId);
        Empresa empresa = empresaDoUsuario(empresaId, usuarioId);
        validarEmpresaAtiva(empresa);

        ExercicioContabil exercicio = exercicioDaEmpresa(empresaId, exercicioId);
        if (inicio.isAfter(fim) || inicio.isBefore(exercicio.getDataInicio()) || fim.isAfter(exercicio.getDataFim())) {
            throw new IllegalArgumentException("O intervalo deve estar contido nas datas do exercício.");
        }

        return new Contexto(empresa, exercicio);
    }

    Usuario usuarioAtivo(UUID usuarioId) {
        if (usuarioId == null) {
            throw new IllegalArgumentException("Usuário deve ser informado.");
        }
        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        validarUsuarioAtivo(usuario);
        return usuario;
    }

    void validarUsuarioAtivo(Usuario usuario) {
        if (!usuario.isAtivo()) {
            throw new IllegalStateException("O usuário deve estar ativo.");
        }
    }

    Empresa empresaDoUsuario(UUID empresaId, UUID usuarioId) {
        if (empresaId == null) {
            throw new IllegalArgumentException("Empresa deve ser informada.");
        }
        return empresas.findByIdAndUsuarioId(empresaId, usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa não encontrada ou não pertence ao usuário."));
    }

    Empresa bloquearEmpresa(UUID empresaId, UUID usuarioId, LockModeType bloqueio) {
        Usuario usuario = usuarioAtivo(usuarioId);
        Empresa empresa = empresaDoUsuario(empresaId, usuarioId);
        em.refresh(empresa, bloqueio);
        em.refresh(usuario, LockModeType.PESSIMISTIC_READ);
        validarUsuarioAtivo(usuario);
        if (!usuarioId.equals(empresa.getUsuario().getId())) {
            throw new IllegalStateException("A titularidade da empresa foi alterada.");
        }
        return empresa;
    }

    void validarEmpresaAtiva(Empresa empresa) {
        if (!empresa.isAtiva() || empresa.getArquivadaEm() != null) {
            throw new IllegalStateException("A empresa deve estar ativa e não arquivada.");
        }
    }

    ExercicioContabil exercicioDaEmpresa(UUID empresaId, UUID exercicioId) {
        if (exercicioId == null) {
            throw new IllegalArgumentException("Exercício deve ser informado.");
        }
        return exercicios.findByIdAndEmpresaId(exercicioId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Exercício não encontrado para a empresa."));
    }

    record Contexto(Empresa empresa, ExercicioContabil exercicio) {
    }
}
