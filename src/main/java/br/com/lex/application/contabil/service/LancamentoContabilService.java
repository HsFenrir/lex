package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.PartidaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusExercicio;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.StatusPeriodo;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.LancamentoContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** O usuarioId deve identificar o usuário autenticado pelo chamador. */
@Service
public class LancamentoContabilService {

    private final UsuarioRepository usuarioRepository;
    private final EmpresaRepository empresaRepository;
    private final ExercicioContabilRepository exercicioRepository;
    private final PeriodoContabilRepository periodoRepository;
    private final PlanoContasRepository planoContasRepository;
    private final LancamentoContabilRepository lancamentoRepository;
    private final ContaContabilRepository contaRepository;
    private final PartidaContabilRepository partidaRepository;

    public LancamentoContabilService(
            UsuarioRepository usuarioRepository,
            EmpresaRepository empresaRepository,
            ExercicioContabilRepository exercicioRepository,
            PeriodoContabilRepository periodoRepository,
            PlanoContasRepository planoContasRepository,
            LancamentoContabilRepository lancamentoRepository,
            ContaContabilRepository contaRepository,
            PartidaContabilRepository partidaRepository
    ) {
        this.usuarioRepository = usuarioRepository;
        this.empresaRepository = empresaRepository;
        this.exercicioRepository = exercicioRepository;
        this.periodoRepository = periodoRepository;
        this.planoContasRepository = planoContasRepository;
        this.lancamentoRepository = lancamentoRepository;
        this.contaRepository = contaRepository;
        this.partidaRepository = partidaRepository;
    }

    @Transactional
    public LancamentoContabil criarRascunho(
            UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId,
            LocalDate data, String historico
    ) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);

        ExercicioContabil exercicio = exercicioRepository
                .findByIdAndEmpresaId(exercicioId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Exercício não encontrado na empresa."));
        PeriodoContabil periodo = periodoRepository
                .findByIdAndEmpresaIdAndExercicioId(periodoId, empresaId, exercicioId)
                .orElseThrow(() -> new IllegalArgumentException("Período não encontrado no exercício da empresa."));

        if (exercicio.getStatus() != StatusExercicio.ABERTO) {
            throw new IllegalStateException("O exercício deve estar aberto.");
        }
        if (periodo.getStatus() != StatusPeriodo.ABERTO) {
            throw new IllegalStateException("O período deve estar aberto.");
        }
        if (data == null || data.isBefore(periodo.getDataInicio()) || data.isAfter(periodo.getDataFim())) {
            throw new IllegalArgumentException("A data do lançamento deve estar dentro do período.");
        }

        PlanoContas plano = planoContasRepository.findByEmpresaId(empresaId)
                .orElseThrow(() -> new IllegalStateException("A empresa não possui plano de contas."));
        return lancamentoRepository.save(new LancamentoContabil(
                empresa, exercicio, periodo, plano, usuario, data, historico, OrigemLancamento.MANUAL
        ));
    }

    @Transactional
    public PartidaContabil adicionarPartida(
            UUID empresaId, UUID usuarioId, UUID lancamentoId, UUID contaId,
            TipoPartida tipo, BigDecimal valor, String historico
    ) {
        LancamentoContabil lancamento = bloquearRascunhoAutorizado(empresaId, usuarioId, lancamentoId);
        ContaContabil conta = contaRepository.findByIdAndPlanoContasId(contaId, lancamento.getPlanoContasId())
                .orElseThrow(() -> new IllegalArgumentException("Conta não encontrada no plano do lançamento."));

        return partidaRepository.save(new PartidaContabil(lancamento, conta, tipo, valor, historico));
    }

    @Transactional
    public void removerPartida(UUID empresaId, UUID usuarioId, UUID lancamentoId, UUID partidaId) {
        LancamentoContabil lancamento = bloquearRascunhoAutorizado(empresaId, usuarioId, lancamentoId);
        PartidaContabil partida = partidaRepository.findByIdAndLancamentoId(partidaId, lancamento.getId())
                .orElseThrow(() -> new IllegalArgumentException("Partida não encontrada no lançamento."));
        partidaRepository.delete(partida);
    }

    private Usuario buscarUsuarioAtivo(UUID usuarioId) {
        if (usuarioId == null) {
            throw new IllegalArgumentException("O usuário deve ser informado.");
        }
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        if (!usuario.isAtivo()) {
            throw new IllegalStateException("O usuário deve estar ativo.");
        }
        return usuario;
    }

    private Empresa buscarEmpresaAutorizada(UUID empresaId, UUID usuarioId) {
        Empresa empresa = empresaRepository.findByIdAndUsuarioId(empresaId, usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa não encontrada ou não pertence ao usuário."));
        if (!empresa.isAtiva() || empresa.getArquivadaEm() != null) {
            throw new IllegalStateException("A empresa deve estar ativa e não arquivada.");
        }
        return empresa;
    }

    private LancamentoContabil bloquearRascunhoAutorizado(UUID empresaId, UUID usuarioId, UUID lancamentoId) {
        buscarUsuarioAtivo(usuarioId);
        buscarEmpresaAutorizada(empresaId, usuarioId);
        LancamentoContabil lancamento = lancamentoRepository.buscarParaAlteracao(lancamentoId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Lançamento não encontrado na empresa."));
        if (lancamento.getStatus() != StatusLancamento.RASCUNHO) {
            throw new IllegalStateException("Somente lançamentos em rascunho podem ter partidas alteradas.");
        }
        return lancamento;
    }
}
