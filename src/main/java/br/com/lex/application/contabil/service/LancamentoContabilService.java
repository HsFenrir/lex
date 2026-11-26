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
import br.com.lex.domain.contabil.repository.SequenciaLancamentoRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final SequenciaLancamentoRepository sequenciaRepository;
    private final EntityManager entityManager;

    public LancamentoContabilService(
            UsuarioRepository usuarioRepository,
            EmpresaRepository empresaRepository,
            ExercicioContabilRepository exercicioRepository,
            PeriodoContabilRepository periodoRepository,
            PlanoContasRepository planoContasRepository,
            LancamentoContabilRepository lancamentoRepository,
            ContaContabilRepository contaRepository,
            PartidaContabilRepository partidaRepository,
            SequenciaLancamentoRepository sequenciaRepository,
            EntityManager entityManager
    ) {
        this.usuarioRepository = usuarioRepository;
        this.empresaRepository = empresaRepository;
        this.exercicioRepository = exercicioRepository;
        this.periodoRepository = periodoRepository;
        this.planoContasRepository = planoContasRepository;
        this.lancamentoRepository = lancamentoRepository;
        this.contaRepository = contaRepository;
        this.partidaRepository = partidaRepository;
        this.sequenciaRepository = sequenciaRepository;
        this.entityManager = entityManager;
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

        validarCalendario(exercicio, periodo, data);

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

    @Transactional
    public LancamentoContabil confirmarLancamento(UUID empresaId, UUID usuarioId, UUID lancamentoId) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);
        LancamentoContabil lancamento = buscarParaAlteracao(empresaId, lancamentoId);
        entityManager.flush();
        entityManager.refresh(lancamento, LockModeType.PESSIMISTIC_WRITE);
        validarRascunho(lancamento, "O lançamento deve estar em rascunho.");
        if (lancamento.getOrigem() == OrigemLancamento.ESTORNO) {
            throw new IllegalStateException("A confirmação de estorno exige o tratamento do lançamento original.");
        }

        ExercicioContabil exercicio = exercicioRepository.findByIdAndEmpresaId(lancamento.getExercicioId(), empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Exercício não encontrado na empresa."));
        PeriodoContabil periodo = periodoRepository
                .findByIdAndEmpresaIdAndExercicioId(lancamento.getPeriodoId(), empresaId, exercicio.getId())
                .orElseThrow(() -> new IllegalArgumentException("Período não encontrado no exercício da empresa."));

        // Ordem: lançamento, período, exercício, empresa, usuário, contas por ID, sequência.
        // Os três primeiros vínculos protegidos após o lançamento seguem a ordem do trigger da V5.
        entityManager.refresh(periodo, LockModeType.PESSIMISTIC_READ);
        entityManager.refresh(exercicio, LockModeType.PESSIMISTIC_READ);
        entityManager.refresh(empresa, LockModeType.PESSIMISTIC_READ);
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_READ);
        validarUsuarioAtivo(usuario);
        if (!empresa.getUsuario().getId().equals(usuarioId)) {
            throw new IllegalArgumentException("Empresa não encontrada ou não pertence ao usuário.");
        }
        validarEmpresaAtiva(empresa);
        validarCalendario(exercicio, periodo, lancamento.getData());
        validarPartidasParaConfirmacao(lancamento);

        long numero = sequenciaRepository.alocarNumero(empresaId, exercicio.getId())
                .orElseThrow(() -> new IllegalStateException("A numeração contábil do exercício atingiu o limite."));
        lancamento.confirmar(numero, usuario, OffsetDateTime.now());
        lancamentoRepository.flush();
        return lancamento;
    }

    private void validarPartidasParaConfirmacao(LancamentoContabil lancamento) {
        List<PartidaContabil> partidas = partidaRepository.findAllByLancamentoIdOrderByIdAsc(lancamento.getId());
        if (partidas.size() < 2) {
            throw new IllegalStateException("Um lançamento deve possuir pelo menos duas partidas para confirmação.");
        }
        Set<UUID> contas = new HashSet<>();
        BigDecimal debitos = BigDecimal.ZERO;
        BigDecimal creditos = BigDecimal.ZERO;
        for (PartidaContabil partida : partidas) {
            contas.add(partida.getContaContabilId());
            BigDecimal valor = PartidaContabil.normalizarValor(partida.getValor());
            switch (partida.getTipo()) {
                case DEBITO -> debitos = debitos.add(valor);
                case CREDITO -> creditos = creditos.add(valor);
            }
        }
        if (debitos.compareTo(creditos) != 0) {
            throw new IllegalStateException("Os totais de débito e crédito devem ser iguais.");
        }
        if (contaRepository.bloquearContasAptas(lancamento.getPlanoContasId(), contas).size() != contas.size()) {
            throw new IllegalStateException("Todas as contas devem ser analíticas, ativas e do plano do lançamento.");
        }
    }

    private void validarCalendario(ExercicioContabil exercicio, PeriodoContabil periodo, LocalDate data) {
        if (exercicio.getStatus() != StatusExercicio.ABERTO) {
            throw new IllegalStateException("O exercício deve estar aberto.");
        }
        if (periodo.getStatus() != StatusPeriodo.ABERTO) {
            throw new IllegalStateException("O período deve estar aberto.");
        }
        if (data == null || data.isBefore(periodo.getDataInicio()) || data.isAfter(periodo.getDataFim())) {
            throw new IllegalArgumentException("A data do lançamento deve estar dentro do período.");
        }
    }

    private Usuario buscarUsuarioAtivo(UUID usuarioId) {
        if (usuarioId == null) {
            throw new IllegalArgumentException("O usuário deve ser informado.");
        }
        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado."));
        validarUsuarioAtivo(usuario);
        return usuario;
    }

    private void validarUsuarioAtivo(Usuario usuario) {
        if (!usuario.isAtivo()) {
            throw new IllegalStateException("O usuário deve estar ativo.");
        }
    }

    private Empresa buscarEmpresaAutorizada(UUID empresaId, UUID usuarioId) {
        Empresa empresa = empresaRepository.findByIdAndUsuarioId(empresaId, usuarioId)
                .orElseThrow(() -> new IllegalArgumentException("Empresa não encontrada ou não pertence ao usuário."));
        validarEmpresaAtiva(empresa);
        return empresa;
    }

    private void validarEmpresaAtiva(Empresa empresa) {
        if (!empresa.isAtiva() || empresa.getArquivadaEm() != null) {
            throw new IllegalStateException("A empresa deve estar ativa e não arquivada.");
        }
    }

    private LancamentoContabil bloquearRascunhoAutorizado(UUID empresaId, UUID usuarioId, UUID lancamentoId) {
        buscarUsuarioAtivo(usuarioId);
        buscarEmpresaAutorizada(empresaId, usuarioId);
        LancamentoContabil lancamento = buscarParaAlteracao(empresaId, lancamentoId);
        validarRascunho(lancamento, "Somente lançamentos em rascunho podem ter partidas alteradas.");
        return lancamento;
    }

    private LancamentoContabil buscarParaAlteracao(UUID empresaId, UUID lancamentoId) {
        return lancamentoRepository.buscarParaAlteracao(lancamentoId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Lançamento não encontrado na empresa."));
    }

    private void validarRascunho(LancamentoContabil lancamento, String mensagem) {
        if (lancamento.getStatus() != StatusLancamento.RASCUNHO) {
            throw new IllegalStateException(mensagem);
        }
    }
}
