package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaConfirmada;
import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.ReversaoOperacional;
import br.com.lex.domain.contabil.ApuracaoSaldos;
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
import br.com.lex.domain.contabil.repository.AtivoPatrimonialRepository;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository.DadosPartida;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import br.com.lex.domain.contabil.repository.SequenciaLancamentoRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** O usuarioId deve identificar o usuário autenticado pelo chamador. */
@Service
public class LancamentoContabilService {

    private static final BigDecimal LIMITE_PARTIDA = new BigDecimal("99999999999999999.99");
    private static final Set<StatusLancamento> STATUS_ESCRITURADOS =
            Set.of(StatusLancamento.CONFIRMADO, StatusLancamento.ESTORNADO);

    private final UsuarioRepository usuarioRepository;
    private final EmpresaRepository empresaRepository;
    private final ExercicioContabilRepository exercicioRepository;
    private final PeriodoContabilRepository periodoRepository;
    private final PlanoContasRepository planoContasRepository;
    private final LancamentoContabilRepository lancamentoRepository;
    private final ContaContabilRepository contaRepository;
    private final PartidaContabilRepository partidaRepository;
    private final SequenciaLancamentoRepository sequenciaRepository;
    private final MovimentoEstoqueRepository movimentosEstoque;
    private final AtivoPatrimonialRepository ativosPatrimoniais;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

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
            MovimentoEstoqueRepository movimentosEstoque,
            AtivoPatrimonialRepository ativosPatrimoniais,
            EntityManager entityManager, JdbcTemplate jdbc
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
        this.movimentosEstoque = movimentosEstoque;
        this.ativosPatrimoniais = ativosPatrimoniais;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Transactional
    public LancamentoContabil criarRascunho(
            UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId,
            LocalDate data, String historico
    ) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);

        ExercicioContabil exercicio = buscarExercicio(empresaId, exercicioId);
        PeriodoContabil periodo = buscarPeriodo(empresaId, exercicioId, periodoId);

        bloquearEValidarDestino(exercicio, periodo, empresa, usuario, data);

        PlanoContas plano = planoContasRepository.findByEmpresaId(empresaId)
                .orElseThrow(() -> new IllegalStateException("A empresa não possui plano de contas."));
        return lancamentoRepository.save(new LancamentoContabil(
                empresa, exercicio, periodo, plano, usuario, data, historico, OrigemLancamento.MANUAL
        ));
    }

    @Transactional
    public AberturaConfirmada registrarSaldosIniciais(AberturaContabil abertura) {
        if (abertura == null) {
            throw new IllegalArgumentException("A solicitação de abertura deve ser informada.");
        }
        Usuario usuario = buscarUsuarioAtivo(abertura.usuarioId());
        Empresa empresa = buscarEmpresaAutorizada(abertura.empresaId(), abertura.usuarioId());
        ExercicioContabil exercicio = buscarExercicio(empresa.getId(), abertura.exercicioId());
        PeriodoContabil periodo = buscarPeriodo(empresa.getId(), exercicio.getId(), abertura.periodoId());
        bloquearEValidarDestino(exercicio, periodo, empresa, usuario, abertura.data());
        if (abertura.saldos().size() < 2) {
            throw new IllegalArgumentException("Informe pelo menos duas contas com saldo.");
        }

        PlanoContas plano = planoContasRepository.findByEmpresaId(empresa.getId())
                .orElseThrow(() -> new IllegalStateException("A empresa não possui plano de contas."));
        LancamentoContabil lancamento = new LancamentoContabil(
                empresa, exercicio, periodo, plano, usuario, abertura.data(), abertura.historico(), OrigemLancamento.ABERTURA
        );
        lancamentoRepository.save(lancamento);
        Set<UUID> contasInformadas = new HashSet<>();
        for (AberturaContabil.SaldoPorConta saldo : abertura.saldos()) {
            if (saldo.contaId() == null || !contasInformadas.add(saldo.contaId())) {
                throw new IllegalArgumentException("Informe cada conta uma única vez na abertura.");
            }
            ContaContabil conta = buscarContaDoLancamento(lancamento, saldo.contaId());
            partidaRepository.save(new PartidaContabil(lancamento, conta, saldo.tipo(), saldo.valor(), null));
        }
        partidaRepository.flush();
        validarConfirmacao(lancamento, empresa, usuario);
        registrarConfirmacao(lancamento, usuario);
        return new AberturaConfirmada(lancamento.getId(), empresa.getId(), exercicio.getId(), periodo.getId(),
                lancamento.getNumero(), lancamento.getData(), lancamento.getOrigem(),
                lancamento.getConfirmadoPor().getId(), lancamento.getConfirmadoEm());
    }

    @Transactional
    public PartidaContabil adicionarPartida(
            UUID empresaId, UUID usuarioId, UUID lancamentoId, UUID contaId,
            TipoPartida tipo, BigDecimal valor, String historico
    ) {
        LancamentoContabil lancamento = bloquearRascunhoAutorizado(empresaId, usuarioId, lancamentoId);
        ContaContabil conta = buscarContaDoLancamento(lancamento, contaId);

        return partidaRepository.save(new PartidaContabil(lancamento, conta, tipo, valor, historico));
    }

    private ContaContabil buscarContaDoLancamento(LancamentoContabil lancamento, UUID contaId) {
        return contaRepository.findByIdAndPlanoContasId(contaId, lancamento.getPlanoContasId())
                .orElseThrow(() -> new IllegalArgumentException("Conta não encontrada no plano do lançamento."));
    }

    static void adicionarSaldoEmPartidas(List<AberturaContabil.SaldoPorConta> partidas, UUID contaId,
                                         TipoPartida tipo, BigDecimal restante) {
        while (restante.signum() > 0) {
            BigDecimal valor = restante.min(LIMITE_PARTIDA);
            partidas.add(new AberturaContabil.SaldoPorConta(contaId, tipo, valor));
            restante = restante.subtract(valor);
        }
    }

    LancamentoContabil escriturarTecnico(Empresa empresa, Usuario usuario, ExercicioContabil exercicio,
                                         PeriodoContabil periodo, LocalDate data, String historico,
                                         OrigemLancamento origem, List<AberturaContabil.SaldoPorConta> saldos) {
        PlanoContas plano = planoContasRepository.findByEmpresaId(empresa.getId())
                .orElseThrow(() -> new IllegalStateException("A empresa não possui plano de contas."));
        LancamentoContabil lancamento = lancamentoRepository.save(
                new LancamentoContabil(empresa, exercicio, periodo, plano, usuario, data, historico, origem));
        for (AberturaContabil.SaldoPorConta saldo : saldos) {
            ContaContabil conta = buscarContaDoLancamento(lancamento, saldo.contaId());
            partidaRepository.save(new PartidaContabil(lancamento, conta, saldo.tipo(), saldo.valor(), null));
        }
        partidaRepository.flush();
        validarCalendario(exercicio, periodo, data);
        validarPartidasParaConfirmacao(lancamento, buscarPartidas(lancamento));
        registrarConfirmacao(lancamento, usuario);
        return lancamento;
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
        LancamentoContabil lancamento = bloquearLancamentoAtualizado(empresaId, lancamentoId);
        validarRascunho(lancamento, "O lançamento deve estar em rascunho.");
        if (lancamento.getOrigem() == OrigemLancamento.ESTORNO) {
            throw new IllegalStateException("A confirmação de estorno exige o tratamento do lançamento original.");
        }
        if (lancamento.getOrigem() == OrigemLancamento.ENCERRAMENTO) {
            throw new IllegalStateException("A confirmação técnica deve ocorrer no fechamento anual.");
        }

        validarConfirmacao(lancamento, empresa, usuario);
        registrarConfirmacao(lancamento, usuario);
        return lancamento;
    }

    @Transactional
    public LancamentoContabil criarRascunhoEstorno(
            UUID empresaId, UUID usuarioId, UUID lancamentoOriginalId, UUID exercicioDestinoId,
            UUID periodoDestinoId, LocalDate data, String historico
    ) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);
        LancamentoContabil original = bloquearLancamentoAtualizado(empresaId, lancamentoOriginalId);
        validarOriginalConfirmado(original);
        validarOrigemEstornavel(original);
        if (lancamentoRepository.findByEstornoDeIdAndEmpresaId(original.getId(), empresaId).isPresent()) {
            throw new IllegalStateException("O lançamento original já possui um estorno associado.");
        }
        validarDataEstorno(original, data);

        ExercicioContabil exercicio = buscarExercicio(empresaId, exercicioDestinoId);
        PeriodoContabil periodo = buscarPeriodo(empresaId, exercicioDestinoId, periodoDestinoId);
        bloquearEValidarDestino(exercicio, periodo, empresa, usuario, data);
        return gravarRascunhoInvertido(original, exercicio, periodo, usuario, data, historico);
    }

    private LancamentoContabil gravarRascunhoInvertido(LancamentoContabil original, ExercicioContabil exercicio,
                                                       PeriodoContabil periodo, Usuario usuario, LocalDate data,
                                                       String historico) {
        List<DadosPartida> partidas = buscarPartidas(original);
        validarPartidasParaConfirmacao(original, partidas);

        LancamentoContabil estorno = lancamentoRepository.save(
                LancamentoContabil.criarEstorno(original, exercicio, periodo, usuario, data, historico)
        );
        partidaRepository.saveAllAndFlush(partidas.stream()
                .map(partida -> new PartidaContabil(estorno, contaRepository.getReferenceById(partida.getContaContabilId()),
                        inverter(partida.getTipo()), partida.getValor(), partida.getHistorico()))
                .toList());
        return estorno;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    EstornoOperacional prepararEstornoOperacional(UUID empresaId, UUID usuarioId, UUID lancamentoId,
                                                  OrigemLancamento origem, UUID periodoId, LocalDate data) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);
        LancamentoContabil original = bloquearLancamentoAtualizado(empresaId, lancamentoId);
        validarOriginalConfirmado(original);
        if (original.getOrigem() != origem
                || lancamentoRepository.findByEstornoDeIdAndEmpresaId(original.getId(), empresaId).isPresent()) {
            throw new IllegalStateException("A operação não corresponde à origem esperada ou já possui estorno.");
        }
        validarDataEstorno(original, data);
        ExercicioContabil exercicio = buscarExercicio(empresaId, original.getExercicioId());
        PeriodoContabil periodoOriginal = buscarPeriodo(empresaId, exercicio.getId(), original.getPeriodoId());
        PeriodoContabil destino = buscarPeriodo(empresaId, exercicio.getId(), periodoId);
        if (data.isBefore(destino.getDataInicio()) || data.isAfter(destino.getDataFim())) {
            throw new IllegalArgumentException("A data da reversão deve estar dentro do período de destino.");
        }
        // Os períodos são bloqueados em ordem cronológica antes do exercício e do controle auxiliar.
        entityManager.refresh(periodoOriginal, LockModeType.PESSIMISTIC_READ);
        bloquearEValidarDestino(exercicio, destino, empresa, usuario, data);
        validarCalendario(exercicio, periodoOriginal, original.getData());
        validarContinuidade(empresaId, exercicio);
        return new EstornoOperacional(original, exercicio, destino, usuario, data);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    LancamentoContabil escriturarEstornoOperacional(EstornoOperacional operacao, String historico) {
        LancamentoContabil original = operacao.original();
        LancamentoContabil estorno = gravarRascunhoInvertido(original, operacao.exercicio(), operacao.periodo(),
                operacao.usuario(), operacao.data(), historico);
        confirmarInversao(original, estorno, operacao.usuario(), buscarPartidas(estorno));
        return estorno;
    }

    @Transactional
    public LancamentoContabil confirmarEstorno(UUID empresaId, UUID usuarioId, UUID estornoId) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);
        EstornoBloqueado vinculo = bloquearEstorno(empresaId, estornoId);
        LancamentoContabil original = vinculo.original();
        LancamentoContabil estorno = vinculo.estorno();
        validarOrigemEstornavel(original);
        validarDataEstorno(original, estorno.getData());
        if (!original.getPlanoContasId().equals(estorno.getPlanoContasId())) {
            throw new IllegalStateException("O estorno deve utilizar o plano de contas do original.");
        }

        List<DadosPartida> partidas = validarConfirmacao(estorno, empresa, usuario);
        confirmarInversao(original, estorno, usuario, partidas);
        return estorno;
    }

    private void confirmarInversao(LancamentoContabil original, LancamentoContabil estorno, Usuario usuario,
                                    List<DadosPartida> partidas) {
        if (!contarPartidas(buscarPartidas(original), true).equals(contarPartidas(partidas, false))) {
            throw new IllegalStateException("As partidas do estorno não correspondem à inversão integral do original.");
        }
        registrarConfirmacao(estorno, usuario);
        original.marcarComoEstornado();
        lancamentoRepository.flush();
    }

    LancamentoContabil buscarEstornoEfetivo(LancamentoContabil original) {
        if (original.getStatus() != StatusLancamento.ESTORNADO) {
            return null;
        }
        return lancamentoRepository.findByEstornoDeIdAndEmpresaId(original.getId(), original.getEmpresaId())
                .filter(estorno -> estorno.getStatus() == StatusLancamento.CONFIRMADO)
                .orElseThrow(() -> new IllegalStateException("O lançamento estornado não possui inversão confirmada."));
    }

    ReversaoOperacional auditarEstornoOperacional(UUID empresaId, UUID usuarioId, String entidade, UUID operacaoId,
                                                 LocalDate data, LancamentoContabil estorno, String historico) {
        if (historico == null || historico.isBlank()) {
            throw new IllegalArgumentException("Informe o histórico da reversão.");
        }
        var reversao = referenciaEstorno(operacaoId, estorno, data, usuarioId);
        jdbc.update("""
                insert into auditoria_evento (empresa_id, usuario_id, entidade, entidade_id, acao, dados_novos)
                values (?, ?, ?, ?, 'ESTORNAR', jsonb_build_object(
                    'lancamentoOriginalId', cast(? as uuid), 'lancamentoEstornoId', cast(? as uuid),
                    'numeroContabil', cast(? as bigint), 'data', cast(? as date), 'historico', cast(? as text)))
                """, empresaId, usuarioId, entidade, operacaoId, reversao.lancamentoOriginalId(),
                reversao.lancamentoEstornoId(), reversao.numeroContabil(), data, historico.trim());
        return reversao;
    }

    static ReversaoOperacional referenciaEstorno(UUID operacaoId, LancamentoContabil estorno,
                                                  LocalDate data, UUID usuarioId) {
        return new ReversaoOperacional(operacaoId, estorno == null ? null : estorno.getEstornoDeId(),
                estorno == null ? null : estorno.getId(), estorno == null ? null : estorno.getNumero(), data, usuarioId);
    }

    @Transactional
    public void cancelarRascunhoEstorno(UUID empresaId, UUID usuarioId, UUID estornoId) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);
        LancamentoContabil estorno = bloquearEstorno(empresaId, estornoId).estorno();
        bloquearEValidarAutorizacao(empresa, usuario);

        partidaRepository.deleteAll(partidaRepository.findAllByLancamentoIdOrderByIdAsc(estorno.getId()));
        partidaRepository.flush();
        lancamentoRepository.delete(estorno);
        lancamentoRepository.flush();
    }

    private EstornoBloqueado bloquearEstorno(UUID empresaId, UUID estornoId) {
        LancamentoContabil referencia = lancamentoRepository.findByIdAndEmpresaId(estornoId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Lançamento não encontrado na empresa."));
        UUID originalId = referencia.getEstornoDeId();
        if (referencia.getOrigem() != OrigemLancamento.ESTORNO || originalId == null) {
            throw new IllegalArgumentException("O lançamento informado não é um estorno.");
        }

        // Criação, confirmação e cancelamento serializam a reserva pelo original primeiro.
        LancamentoContabil original = bloquearLancamentoAtualizado(empresaId, originalId);
        LancamentoContabil estorno = bloquearLancamentoAtualizado(empresaId, estornoId);
        if (estorno.getOrigem() != OrigemLancamento.ESTORNO || !originalId.equals(estorno.getEstornoDeId())) {
            throw new IllegalStateException("O vínculo do estorno foi alterado.");
        }
        validarRascunho(estorno, "O estorno deve estar em rascunho.");
        validarOriginalConfirmado(original);
        return new EstornoBloqueado(original, estorno);
    }

    private void validarOriginalConfirmado(LancamentoContabil original) {
        if (original.getStatus() != StatusLancamento.CONFIRMADO) {
            throw new IllegalStateException("O lançamento original deve estar confirmado.");
        }
    }

    private void validarOrigemEstornavel(LancamentoContabil original) {
        switch (original.getOrigem()) {
            case MANUAL -> { }
            case ABERTURA -> {
                ExercicioContabil exercicio = buscarExercicio(original.getEmpresaId(), original.getExercicioId());
                if (exercicio.getExercicioOrigemId() != null) {
                    throw new IllegalStateException("A abertura automatizada não pode ser estornada pelo fluxo genérico.");
                }
                if (movimentosEstoque.existeVinculado(original.getEmpresaId(), original.getId())) {
                    throw new IllegalStateException("A abertura vinculada a lotes iniciais exige reversão pelo módulo de estoque.");
                }
                if (ativosPatrimoniais.existsByEmpresaIdAndLancamentoAberturaId(original.getEmpresaId(), original.getId())) {
                    throw new IllegalStateException("A abertura vinculada a fichas importadas exige reversão pelo módulo patrimonial.");
                }
            }
            case ESTORNO -> throw new IllegalStateException("Um lançamento de estorno não pode ser estornado.");
            case ENCERRAMENTO -> throw new IllegalStateException("Um lançamento de encerramento não pode ser estornado.");
            default -> throw new IllegalStateException("O lançamento exige reversão pelo módulo de origem.");
        }
    }

    private void validarDataEstorno(LancamentoContabil original, LocalDate data) {
        if (data == null || data.isBefore(original.getData())) {
            throw new IllegalArgumentException("A data do estorno deve ser igual ou posterior à data do original.");
        }
    }

    private Map<ChavePartida, Long> contarPartidas(List<DadosPartida> partidas, boolean inverterTipo) {
        return partidas.stream().collect(Collectors.groupingBy(
                partida -> new ChavePartida(partida.getContaContabilId(),
                        inverterTipo ? inverter(partida.getTipo()) : partida.getTipo(),
                        PartidaContabil.normalizarValor(partida.getValor())),
                Collectors.counting()
        ));
    }

    private TipoPartida inverter(TipoPartida tipo) {
        return switch (tipo) {
            case DEBITO -> TipoPartida.CREDITO;
            case CREDITO -> TipoPartida.DEBITO;
        };
    }

    private List<DadosPartida> buscarPartidas(LancamentoContabil lancamento) {
        return partidaRepository.findAllProjetadasByLancamentoIdOrderByIdAsc(lancamento.getId());
    }

    private List<DadosPartida> validarConfirmacao(LancamentoContabil lancamento, Empresa empresa, Usuario usuario) {
        ExercicioContabil exercicio = buscarExercicio(empresa.getId(), lancamento.getExercicioId());
        PeriodoContabil periodo = buscarPeriodo(empresa.getId(), exercicio.getId(), lancamento.getPeriodoId());
        bloquearEValidarDestino(exercicio, periodo, empresa, usuario, lancamento.getData());
        if (lancamento.getOrigem() == OrigemLancamento.ABERTURA) {
            validarPoliticaAbertura(empresa.getId(), exercicio);
        } else {
            validarContinuidade(empresa.getId(), exercicio);
        }
        List<DadosPartida> partidas = buscarPartidas(lancamento);
        validarPartidasParaConfirmacao(lancamento, partidas);
        return partidas;
    }

    void validarContinuidade(UUID empresaId, ExercicioContabil exercicio) {
        if (exercicio.getExercicioOrigemId() == null
                && exercicioRepository.existsByEmpresaIdAndAnoLessThan(empresaId, exercicio.getAno())) {
            throw new IllegalStateException("Conclua o fechamento e a continuidade do exercício anterior antes de confirmar.");
        }
    }

    void validarAcessoOperacional(UUID empresaId, UUID usuarioId) {
        buscarUsuarioAtivo(usuarioId);
        buscarEmpresaAutorizada(empresaId, usuarioId);
    }

    LancamentoContabil bloquearAberturaParaImportacao(UUID empresaId, UUID usuarioId, UUID aberturaId,
                                                      LockModeType bloqueioExercicio) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);
        LancamentoContabil abertura = bloquearLancamentoAtualizado(empresaId, aberturaId);
        if (abertura.getOrigem() != OrigemLancamento.ABERTURA || abertura.getStatus() != StatusLancamento.CONFIRMADO) {
            throw new IllegalStateException("Informe uma abertura de migração confirmada.");
        }
        ExercicioContabil exercicio = buscarExercicio(empresaId, abertura.getExercicioId());
        PeriodoContabil periodo = buscarPeriodo(empresaId, exercicio.getId(), abertura.getPeriodoId());
        bloquearEValidarDestino(exercicio, periodo, empresa, usuario, abertura.getData(), bloqueioExercicio);
        if (exercicio.getExercicioOrigemId() != null
                || exercicioRepository.existsByEmpresaIdAndAnoLessThan(empresaId, exercicio.getAno())) {
            throw new IllegalStateException("A importação exige a abertura do primeiro exercício da empresa.");
        }
        if (lancamentoRepository.findByEstornoDeIdAndEmpresaId(aberturaId, empresaId).isPresent()) {
            throw new IllegalStateException("Cancele o rascunho de estorno da abertura antes da importação.");
        }
        return abertura;
    }

    Map<UUID, BigDecimal> saldosDaAbertura(LancamentoContabil abertura) {
        Map<UUID, ApuracaoSaldos> apuracoes = new HashMap<>();
        for (DadosPartida partida : buscarPartidas(abertura)) {
            apuracoes.computeIfAbsent(partida.getContaContabilId(), id -> new ApuracaoSaldos(abertura.getData()))
                    .registrar(abertura.getData(), partida.getTipo(), partida.getValor());
        }
        return apuracoes.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                item -> item.getValue().saldoAtual().valor()));
    }

    void validarDestinoOperacional(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId, LocalDate data) {
        Usuario usuario = buscarUsuarioAtivo(usuarioId);
        Empresa empresa = buscarEmpresaAutorizada(empresaId, usuarioId);
        ExercicioContabil exercicio = buscarExercicio(empresaId, exercicioId);
        PeriodoContabil periodo = buscarPeriodo(empresaId, exercicioId, periodoId);
        bloquearEValidarDestino(exercicio, periodo, empresa, usuario, data);
        validarContinuidade(empresaId, exercicio);
        validarDataDaMigracao(empresaId, exercicioId, data);
    }

    private void bloquearEValidarDestino(
            ExercicioContabil exercicio, PeriodoContabil periodo, Empresa empresa, Usuario usuario, LocalDate data
    ) {
        bloquearEValidarDestino(exercicio, periodo, empresa, usuario, data, LockModeType.PESSIMISTIC_READ);
    }

    private void bloquearEValidarDestino(ExercicioContabil exercicio, PeriodoContabil periodo, Empresa empresa,
                                          Usuario usuario, LocalDate data, LockModeType bloqueioExercicio) {
        // Após os lançamentos: período, exercício, empresa, usuário, contas por ID e sequência.
        entityManager.refresh(periodo, LockModeType.PESSIMISTIC_READ);
        entityManager.refresh(exercicio, bloqueioExercicio);
        bloquearEValidarAutorizacao(empresa, usuario);
        validarCalendario(exercicio, periodo, data);
    }

    private void bloquearEValidarAutorizacao(Empresa empresa, Usuario usuario) {
        entityManager.refresh(empresa, LockModeType.PESSIMISTIC_READ);
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_READ);
        validarUsuarioAtivo(usuario);
        if (!empresa.getUsuario().getId().equals(usuario.getId())) {
            throw new IllegalArgumentException("Empresa não encontrada ou não pertence ao usuário.");
        }
        validarEmpresaAtiva(empresa);
    }

    private void registrarConfirmacao(LancamentoContabil lancamento, Usuario usuario) {
        long numero = sequenciaRepository.alocarNumero(lancamento.getEmpresaId(), lancamento.getExercicioId())
                .orElseThrow(() -> new IllegalStateException("A numeração contábil do exercício atingiu o limite."));
        // A mesma sequência de todas as confirmações decide qual operação escritura primeiro.
        if (lancamento.getOrigem() == OrigemLancamento.ABERTURA && numero != 1L) {
            throw new IllegalStateException("A abertura deve ser o primeiro lançamento escriturado no exercício.");
        }
        validarLimiteDaAbertura(lancamento);
        lancamento.confirmar(numero, usuario, OffsetDateTime.now());
        lancamentoRepository.flush();
    }

    void validarIntegralizacaoEstoque(UUID empresaId, LocalDate data) {
        UUID mercadorias = contaRepository.buscarContaDoModeloPadrao(empresaId, EstoqueService.MERCADORIAS)
                .orElseThrow(() -> new IllegalStateException("Conta do modelo padrão ausente no plano: " + EstoqueService.MERCADORIAS));
        var apuracao = new ApuracaoSaldos(data);
        for (var partida : partidaRepository.somarMigracaoDaContaAte(empresaId, mercadorias, data)) {
            apuracao.registrar(data, partida.getTipo(), partida.getValor());
        }
        BigDecimal abertura = apuracao.saldoAtual().valor();
        if (abertura.signum() > 0) {
            BigDecimal importado = movimentosEstoque.somarValorImportadoAte(empresaId, data);
            BigDecimal pendente = ConciliacaoOperacionalService.saldoPendenteDeImportacao(abertura, importado);
            if (pendente.signum() > 0) {
                throw new IllegalStateException("Saldo inicial de Mercadorias pendente de importação: "
                        + pendente.toPlainString() + ". Importe os lotes da abertura antes de comprar ou vender.");
            }
        }
    }

    private void validarLimiteDaAbertura(LancamentoContabil lancamento) {
        if (lancamento.getOrigem() == OrigemLancamento.ABERTURA) {
            return;
        }
        // Em READ COMMITTED, esta instrução vê a abertura confirmada enquanto o UPSERT aguardava a sequência.
        // Com snapshot fixo, o PostgreSQL rejeita a atualização concorrente da sequência se ela mudou fora do snapshot.
        // ABERTURA identifica a migração na V6. O transporte futuro deve ter identificação própria.
        validarDataDaMigracao(lancamento.getEmpresaId(), lancamento.getExercicioId(), lancamento.getData());
    }

    private void validarDataDaMigracao(UUID empresaId, UUID exercicioId, LocalDate dataContabil) {
        lancamentoRepository.buscarDataAberturaMigracao(empresaId, exercicioId)
                .filter(dataContabil::isBefore)
                .ifPresent(data -> {
                    throw new IllegalStateException("A data contábil não pode ser anterior à abertura de migração em " + data + ".");
                });
    }

    private void validarPoliticaAbertura(UUID empresaId, ExercicioContabil exercicio) {
        if (exercicioRepository.existsByEmpresaIdAndAnoLessThan(empresaId, exercicio.getAno())) {
            throw new IllegalStateException("A abertura manual é permitida apenas no primeiro exercício da empresa.");
        }
        if (lancamentoRepository.existsByEmpresaIdAndExercicioIdAndStatusIn(
                empresaId, exercicio.getId(), STATUS_ESCRITURADOS)) {
            if (lancamentoRepository.existsByEmpresaIdAndExercicioIdAndOrigemAndStatusIn(
                    empresaId, exercicio.getId(), OrigemLancamento.ABERTURA, STATUS_ESCRITURADOS)) {
                throw new IllegalStateException("O exercício já possui abertura escriturada, ainda que estornada.");
            }
            throw new IllegalStateException("O exercício já possui movimentos escriturados.");
        }
    }

    private void validarPartidasParaConfirmacao(LancamentoContabil lancamento, List<DadosPartida> partidas) {
        if (partidas.size() < 2) {
            throw new IllegalStateException("Um lançamento deve possuir pelo menos duas partidas para confirmação.");
        }
        Set<UUID> contas = new HashSet<>();
        BigDecimal debitos = BigDecimal.ZERO;
        BigDecimal creditos = BigDecimal.ZERO;
        for (DadosPartida partida : partidas) {
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

    private ExercicioContabil buscarExercicio(UUID empresaId, UUID exercicioId) {
        return exercicioRepository.findByIdAndEmpresaId(exercicioId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Exercício não encontrado na empresa."));
    }

    private PeriodoContabil buscarPeriodo(UUID empresaId, UUID exercicioId, UUID periodoId) {
        return periodoRepository.findByIdAndEmpresaIdAndExercicioId(periodoId, empresaId, exercicioId)
                .orElseThrow(() -> new IllegalArgumentException("Período não encontrado no exercício da empresa."));
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
        LancamentoContabil lancamento = bloquearLancamentoAtualizado(empresaId, lancamentoId);
        validarRascunho(lancamento, "Somente lançamentos em rascunho podem ter partidas alteradas.");
        if (lancamento.getOrigem() == OrigemLancamento.ESTORNO) {
            throw new IllegalStateException("As partidas de um estorno são geradas automaticamente.");
        }
        if (lancamento.getOrigem() == OrigemLancamento.ENCERRAMENTO) {
            throw new IllegalStateException("As partidas do encerramento são geradas automaticamente.");
        }
        return lancamento;
    }

    private LancamentoContabil bloquearLancamentoAtualizado(UUID empresaId, UUID lancamentoId) {
        LancamentoContabil lancamento = buscarParaAlteracao(empresaId, lancamentoId);
        entityManager.flush();
        entityManager.refresh(lancamento, LockModeType.PESSIMISTIC_WRITE);
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

    private record EstornoBloqueado(LancamentoContabil original, LancamentoContabil estorno) { }

    record EstornoOperacional(LancamentoContabil original, ExercicioContabil exercicio, PeriodoContabil periodo,
                              Usuario usuario, LocalDate data) { }

    private record ChavePartida(UUID contaId, TipoPartida tipo, BigDecimal valor) { }
}
