package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.AquisicaoAtivo;
import br.com.lex.application.contabil.dto.ApropriacaoDepreciacao;
import br.com.lex.application.contabil.dto.FichaAtivo;
import br.com.lex.application.contabil.dto.SaldoInicialPatrimonial;
import br.com.lex.application.contabil.dto.ReversaoOperacional;
import br.com.lex.domain.contabil.entity.AtivoPatrimonial;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.PartidaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.ClassificacaoDRE;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusExercicio;
import br.com.lex.domain.contabil.enums.StatusPeriodo;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.AtivoPatrimonialRepository;
import br.com.lex.domain.contabil.repository.AtivoPatrimonialRepository.ValoresImportados;
import br.com.lex.domain.contabil.repository.ApropriacaoDepreciacaoRepository;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.ContaContabilRepository.ContaDoPlano;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;

@Service
@RequiredArgsConstructor
public class AtivoPatrimonialService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    // Referências do modelo padrão V1, provisionado pela V3.
    static final String IMOBILIZADO = "1.2.03";
    private static final String TERRENOS = "1.2.03.001";
    private static final List<String> CONTRAPARTIDAS = List.of("1.1.01.001", "1.1.01.002", "2.1.01.001", "2.2.02.001");
    private static final List<String> DESPESAS_DEPRECIACAO = List.of("6.1.005", "6.2.006");

    private final AtivoPatrimonialRepository ativos;
    private final ApropriacaoDepreciacaoRepository apropriacoes;
    private final PlanoContasRepository planos;
    private final ContaContabilRepository contas;
    private final ExercicioContabilRepository exercicios;
    private final PeriodoContabilRepository periodos;
    private final LancamentoContabilService contabilidade;
    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FichaAtivo registrarAquisicao(AquisicaoAtivo aquisicao) {
        if (aquisicao == null) {
            throw new IllegalArgumentException("Informe os dados da aquisição patrimonial.");
        }
        validarIsolamento();
        UUID empresa = aquisicao.empresaId();
        UUID usuario = aquisicao.usuarioId();
        contabilidade.validarDestinoOperacional(empresa, usuario, aquisicao.exercicioId(), aquisicao.periodoId(),
                aquisicao.dataAquisicao());
        BigDecimal custo = PartidaContabil.normalizarValor(aquisicao.custoAquisicao());
        BigDecimal residual = validarDadosPatrimoniais(aquisicao.dataAquisicao(), aquisicao.dataDisponibilidade(),
                aquisicao.depreciavel(), aquisicao.vidaUtilMeses(), aquisicao.contaDespesaId(), aquisicao.valorResidual(), custo);
        validarCompetenciasDaDisponibilidade(empresa, aquisicao.dataAquisicao().getYear(), aquisicao.dataDisponibilidade(),
                aquisicao.depreciavel() ? custo.subtract(residual) : BigDecimal.ZERO, aquisicao.vidaUtilMeses());
        String codigo = textoObrigatorio(Objects.toString(aquisicao.codigo(), "").toUpperCase(Locale.ROOT),
                50, "Código patrimonial");
        String descricao = textoObrigatorio(aquisicao.descricao(), 200, "Descrição");
        UUID plano = planos.findByEmpresaId(empresa)
                .orElseThrow(() -> new IllegalStateException("A empresa não possui plano de contas.")).getId();
        List<ContaDoPlano> estrutura = contas.buscarEstruturaDoPlano(empresa);
        ContaDoPlano patrimonial = validarContaPatrimonial(empresa, aquisicao.contaPatrimonialId(),
                aquisicao.depreciavel(), estrutura);
        UUID redutora = aquisicao.depreciavel() ? resolverRedutora(estrutura, patrimonial.getId()) : null;
        validarReferencia(empresa, aquisicao.contrapartidaId(), CONTRAPARTIDAS, "Conta de contrapartida incompatível.");
        if (aquisicao.depreciavel()) {
            validarDespesa(empresa, aquisicao.contaDespesaId(), estrutura);
        }

        AtivoPatrimonial ativo = ativos.saveAndFlush(new AtivoPatrimonial(empresa, plano, usuario, codigo, descricao,
                patrimonial.getId(), redutora, aquisicao.contaDespesaId(), aquisicao.dataAquisicao(),
                aquisicao.dataDisponibilidade(), custo, aquisicao.depreciavel(), residual, aquisicao.vidaUtilMeses()));
        var ids = new HashSet<>(List.of(patrimonial.getId(), aquisicao.contrapartidaId()));
        if (aquisicao.depreciavel()) {
            ids.add(redutora);
            ids.add(aquisicao.contaDespesaId());
        }
        if (contas.bloquearContasAptas(plano, ids).size() != ids.size()) {
            throw new IllegalStateException("Todas as contas do ativo devem ser analíticas e ativas no plano da empresa.");
        }
        LancamentoContabil lancamento = contabilidade.escriturarTecnico(
                em.getReference(Empresa.class, empresa), em.getReference(Usuario.class, usuario),
                em.getReference(ExercicioContabil.class, aquisicao.exercicioId()),
                em.getReference(PeriodoContabil.class, aquisicao.periodoId()), aquisicao.dataAquisicao(),
                aquisicao.historico(), OrigemLancamento.ATIVO, List.of(
                        new SaldoPorConta(patrimonial.getId(), TipoPartida.DEBITO, custo),
                        new SaldoPorConta(aquisicao.contrapartidaId(), TipoPartida.CREDITO, custo)));
        ativo.vincularAquisicao(lancamento);
        ativos.flush();
        auditar(ativo);
        return ficha(ativo, List.of());
    }

    /** Cada lote completa as contas patrimoniais selecionadas e suas redutoras na abertura. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<FichaAtivo> importarSaldoInicial(SaldoInicialPatrimonial saldo) {
        if (saldo == null || saldo.bens().isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um bem para a importação patrimonial.");
        }
        validarIsolamento();
        UUID empresa = saldo.empresaId();
        LancamentoContabil abertura = contabilidade.bloquearAberturaParaImportacao(
                empresa, saldo.usuarioId(), saldo.aberturaId(), LockModeType.PESSIMISTIC_WRITE);
        var estrutura = contas.buscarEstruturaDoPlano(empresa);
        var bens = saldo.bens().stream().map(bem -> prepararBemImportado(saldo.usuarioId(), abertura, estrutura, bem)).toList();
        var codigos = new HashSet<String>();
        var idsContas = new HashSet<UUID>();
        for (AtivoPatrimonial bem : bens) {
            if (!codigos.add(bem.getCodigo())) {
                throw new IllegalArgumentException("Código patrimonial duplicado no lote: " + bem.getCodigo());
            }
            idsContas.add(bem.getContaPatrimonialId());
            if (bem.isDepreciavel()) {
                idsContas.add(bem.getContaRedutoraId());
                idsContas.add(bem.getContaDespesaId());
            }
        }
        if (ativos.existsByEmpresaIdAndCodigoIn(empresa, codigos)) {
            throw new IllegalStateException("Já existe um ativo com um dos códigos patrimoniais informados.");
        }
        if (contas.bloquearContasAptas(abertura.getPlanoContasId(), idsContas).size() != idsContas.size()) {
            throw new IllegalStateException("Todas as contas do ativo devem ser analíticas e ativas no plano da empresa.");
        }
        validarLimitesDaAbertura(abertura, bens);
        return ativos.saveAllAndFlush(bens).stream().map(bem -> {
            auditarImportacao(bem, abertura);
            return ficha(bem, List.of());
        }).toList();
    }

    private AtivoPatrimonial prepararBemImportado(UUID usuarioId, LancamentoContabil abertura,
                                                    List<ContaDoPlano> estrutura, SaldoInicialPatrimonial.Bem bem) {
        LocalDate dataAbertura = abertura.getData();
        if (bem.dataAquisicao() == null || bem.dataAquisicao().getYear() < 1 || bem.dataAquisicao().isAfter(dataAbertura)) {
            throw new IllegalArgumentException("A data histórica de aquisição deve ser válida e igual ou anterior à abertura.");
        }
        if (bem.dataDisponibilidade() != null && bem.dataDisponibilidade().getYear() > 9999) {
            throw new IllegalArgumentException("A disponibilidade para uso está fora do calendário contábil suportado.");
        }
        BigDecimal custo = PartidaContabil.normalizarValor(bem.custoHistorico());
        BigDecimal residual = validarDadosPatrimoniais(bem.dataAquisicao(), bem.dataDisponibilidade(),
                bem.depreciavel(), bem.vidaUtilMeses(), bem.contaDespesaId(), bem.valorResidual(), custo);
        BigDecimal acumulado = bem.depreciacaoAcumulada() != null && bem.depreciacaoAcumulada().signum() == 0
                ? ZERO : PartidaContabil.normalizarValor(bem.depreciacaoAcumulada());
        YearMonth marco = validarHistoricoImportado(bem, dataAbertura, custo.subtract(residual), acumulado);
        UUID empresa = abertura.getEmpresaId();
        ContaDoPlano patrimonial = validarContaPatrimonial(empresa, bem.contaPatrimonialId(), bem.depreciavel(), estrutura);
        UUID redutora = null;
        if (bem.depreciavel()) {
            redutora = resolverRedutora(estrutura, patrimonial.getId());
            validarDespesa(empresa, bem.contaDespesaId(), estrutura);
        }
        String codigo = textoObrigatorio(Objects.toString(bem.codigo(), "").toUpperCase(Locale.ROOT), 50, "Código patrimonial");
        String descricao = textoObrigatorio(bem.descricao(), 200, "Descrição");
        var ativo = new AtivoPatrimonial(empresa, abertura.getPlanoContasId(), usuarioId, codigo, descricao,
                patrimonial.getId(), redutora, bem.contaDespesaId(), bem.dataAquisicao(), bem.dataDisponibilidade(),
                custo, bem.depreciavel(), residual, bem.vidaUtilMeses());
        ativo.vincularAbertura(abertura, acumulado, bem.mesesConsumidos(), marco);
        YearMonth primeira = ativo.primeiraCompetenciaLex();
        if (primeira != null && ativo.baseInicialLex().signum() > 0) {
            validarCompetenciasDaDisponibilidade(empresa, dataAbertura.getYear(), primeira.atDay(1),
                    ativo.baseInicialLex(), ativo.vidaUtilInicialLex());
        }
        return ativo;
    }

    private YearMonth validarHistoricoImportado(SaldoInicialPatrimonial.Bem bem, LocalDate abertura,
                                                BigDecimal base, BigDecimal acumulado) {
        YearMonth mesAbertura = YearMonth.from(abertura);
        int consumidos = bem.mesesConsumidos();
        int vidaUtil = bem.depreciavel() ? bem.vidaUtilMeses() : 0;
        boolean disponivelNaAbertura = bem.dataDisponibilidade() != null && !bem.dataDisponibilidade().isAfter(abertura);
        YearMonth marco = bem.mesAberturaJaApropriado() ? mesAbertura.plusMonths(1) : mesAbertura;
        long mesesPossiveis = disponivelNaAbertura
                ? ChronoUnit.MONTHS.between(YearMonth.from(bem.dataDisponibilidade()), marco) : 0;
        if (consumidos < 0 || consumidos > vidaUtil || consumidos > mesesPossiveis
                || (bem.mesAberturaJaApropriado() && consumidos == 0)) {
            throw new IllegalArgumentException("Meses consumidos incompatíveis com a disponibilidade, a abertura ou a vida útil.");
        }
        if (((!bem.depreciavel() || consumidos == 0) && acumulado.signum() > 0)
                || acumulado.compareTo(base) > 0
                || (bem.depreciavel() && consumidos == vidaUtil && acumulado.compareTo(base) != 0)) {
            throw new IllegalArgumentException("Depreciação acumulada incompatível com a base depreciável ou a vida útil consumida.");
        }
        return marco;
    }

    private void validarLimitesDaAbertura(LancamentoContabil abertura, List<AtivoPatrimonial> bens) {
        Map<UUID, BigDecimal> pedido = new HashMap<>();
        for (AtivoPatrimonial bem : bens) {
            pedido.merge(bem.getContaPatrimonialId(), bem.getCustoAquisicao(), BigDecimal::add);
            if (bem.isDepreciavel()) {
                pedido.merge(bem.getContaRedutoraId(), bem.getDepreciacaoInicial().negate(), BigDecimal::add);
            }
        }
        Map<UUID, BigDecimal> saldo = contabilidade.saldosDaAbertura(abertura);
        for (ValoresImportados anterior : ativos.somarImportados(abertura.getEmpresaId(), abertura.getId())) {
            saldo.merge(anterior.getContaPatrimonialId(), anterior.getCusto().negate(), BigDecimal::add);
            if (anterior.getDepreciacao().signum() > 0) {
                saldo.merge(anterior.getContaRedutoraId(), anterior.getDepreciacao(), BigDecimal::add);
            }
        }
        pedido.forEach((conta, valor) -> {
            BigDecimal disponivel = saldo.getOrDefault(conta, ZERO);
            if (valor.compareTo(disponivel) != 0) {
                throw new IllegalArgumentException("O lote deve completar o saldo disponível da abertura na conta " + conta
                        + " (disponível " + disponivel.toPlainString() + ", solicitado " + valor.toPlainString() + ").");
            }
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReversaoOperacional estornarAquisicao(UUID empresaId, UUID usuarioId, UUID ativoId,
                                                 UUID periodoDestinoId, LocalDate data, String historico) {
        validarIsolamento();
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        AtivoPatrimonial ativo = buscarAtivo(empresaId, ativoId);
        if (ativo.isImportado()) {
            throw new IllegalStateException("Uma ficha importada não é uma aquisição regular.");
        }
        var estorno = contabilidade.prepararEstornoOperacional(empresaId, usuarioId,
                ativo.getLancamentoAquisicao().getId(), OrigemLancamento.ATIVO, periodoDestinoId, data);
        ativo = bloquearAtivo(empresaId, ativoId);
        if (apropriacoes.listar(empresaId, ativoId).stream()
                .anyMatch(a -> a.reversao() == null || a.reversao().data().isAfter(data))) {
            throw new IllegalStateException("O ativo possui apropriação efetiva ou histórico posterior à data solicitada.");
        }
        // Uma competência anterior à baixa dos efeitos ainda exigiria depreciação no fechamento.
        YearMonth primeira = ativo.primeiraCompetenciaLex();
        if (primeira != null && ativo.baseInicialLex().signum() > 0 && primeira.atEndOfMonth().isBefore(data)) {
            throw new IllegalStateException("A reversão deixaria depreciação pendente em competência anterior.");
        }
        var lancamento = contabilidade.escriturarEstornoOperacional(estorno, historico);
        return contabilidade.auditarEstornoOperacional(empresaId, usuarioId, "ativo_patrimonial",
                ativoId, data, lancamento, historico);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FichaAtivo registrarDisponibilidade(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId,
                                               UUID ativoId, LocalDate data) {
        validarIsolamento();
        contabilidade.validarDestinoOperacional(empresaId, usuarioId, exercicioId, periodoId, data);
        AtivoPatrimonial ativo = buscarAtivo(empresaId, ativoId);
        validarCompetenciasDaDisponibilidade(empresaId, data.getYear(), data, ativo.baseInicialLex(),
                ativo.vidaUtilInicialLex());
        em.refresh(ativo, LockModeType.PESSIMISTIC_WRITE);
        if (aquisicaoEstornada(ativo)) {
            throw new IllegalStateException("A aquisição do ativo foi estornada.");
        }
        if (!apropriacoes.listar(empresaId, ativoId).isEmpty()) {
            throw new IllegalStateException("O ativo já possui competências de depreciação processadas.");
        }
        ativo.registrarDisponibilidade(data);
        ativos.flush();
        jdbc.update("""
                insert into auditoria_evento
                    (empresa_id, usuario_id, entidade, entidade_id, acao, dados_anteriores, dados_novos)
                values (?, ?, 'ativo_patrimonial', ?, 'REGISTRAR_DISPONIBILIDADE',
                    jsonb_build_object('dataDisponibilidade', null::date, 'situacao', 'AGUARDANDO_DISPONIBILIDADE'),
                    jsonb_build_object('dataDisponibilidade', cast(? as date), 'situacao', 'DISPONIBILIDADE_REGISTRADA'))
                """, empresaId, usuarioId, ativoId, data);
        return ficha(ativo, List.of());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FichaAtivo consultar(UUID empresaId, UUID usuarioId, UUID ativoId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        AtivoPatrimonial ativo = buscarAtivo(empresaId, ativoId);
        return ficha(ativo, apropriacoes.listar(empresaId, ativoId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<FichaAtivo> listar(UUID empresaId, UUID usuarioId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        var historico = apropriacoes.listarDaEmpresa(empresaId).stream()
                .collect(groupingBy(ApropriacaoDepreciacao::ativoId));
        return ativos.findAllByEmpresaIdOrderByCodigoAsc(empresaId).stream()
                .map(ativo -> ficha(ativo, historico.getOrDefault(ativo.getId(), List.of()))).toList();
    }

    AtivoPatrimonial bloquearAtivo(UUID empresaId, UUID ativoId) {
        AtivoPatrimonial ativo = buscarAtivo(empresaId, ativoId);
        em.refresh(ativo, LockModeType.PESSIMISTIC_WRITE);
        return ativo;
    }

    boolean aquisicaoEstornada(AtivoPatrimonial ativo) {
        return ativo.getLancamentoAquisicao() != null
                && ativos.aquisicaoEstornada(ativo.getEmpresaId(), ativo.getLancamentoAquisicao().getId());
    }

    private AtivoPatrimonial buscarAtivo(UUID empresaId, UUID ativoId) {
        return ativos.findByIdAndEmpresaId(ativoId, empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Ativo patrimonial não encontrado na empresa."));
    }

    void validarIsolamento() {
        if (!"read committed".equals(jdbc.queryForObject("show transaction_isolation", String.class))) {
            throw new IllegalStateException("A operação patrimonial exige READ COMMITTED.");
        }
    }

    void bloquearEValidarContasDepreciacao(List<AtivoPatrimonial> bens) {
        if (bens.isEmpty()) {
            return;
        }
        var ids = new HashSet<UUID>();
        for (AtivoPatrimonial ativo : bens) {
            ids.add(ativo.getContaPatrimonialId());
            ids.add(ativo.getContaRedutoraId());
            ids.add(ativo.getContaDespesaId());
        }
        var primeiro = bens.getFirst();
        if (contas.bloquearContasAptas(primeiro.getPlanoContasId(), ids).size() != ids.size()) {
            throw new IllegalStateException("As contas da depreciação devem permanecer analíticas e ativas.");
        }
        UUID empresa = primeiro.getEmpresaId();
        List<ContaDoPlano> estrutura = contas.buscarEstruturaDoPlano(empresa);
        for (AtivoPatrimonial ativo : bens) {
            validarContaPatrimonial(empresa, ativo.getContaPatrimonialId(), true, estrutura);
            if (!ativo.getContaRedutoraId().equals(resolverRedutora(estrutura, ativo.getContaPatrimonialId()))) {
                throw new IllegalStateException("A redutora vinculada à aquisição não corresponde à conta patrimonial.");
            }
            validarDespesa(empresa, ativo.getContaDespesaId(), estrutura);
        }
    }

    private void validarCompetenciasDaDisponibilidade(UUID empresa, int anoOperacao, LocalDate data,
                                                       BigDecimal base, Integer vidaUtil) {
        if (data == null) {
            return;
        }
        if (data.getYear() > 9999) {
            throw new IllegalArgumentException("A disponibilidade para uso está fora do calendário contábil suportado.");
        }
        // Mesmo uma parcela zero é obrigatória até o acumulado arredondado atingir a base.
        int mesesDevidos = base.signum() == 0 ? 1 : base.subtract(new BigDecimal("0.005"))
                .multiply(BigDecimal.valueOf(vidaUtil)).divide(base, 0, RoundingMode.CEILING).intValueExact();
        YearMonth inicio = YearMonth.from(data);
        YearMonth limite = YearMonth.of(9999, 12);
        YearMonth fim = inicio.plusMonths(mesesDevidos - 1L);
        if (fim.isAfter(limite)) {
            fim = limite;
        }
        // O exercício da operação já está bloqueado. Siga a ordem dos anos, sem bloquear períodos futuros.
        short primeiroAnoFuturo = (short) Math.max(anoOperacao + 1, inicio.getYear());
        for (var exercicio : exercicios.findAllByEmpresaIdAndAnoBetweenOrderByAno(
                empresa, primeiroAnoFuturo, (short) fim.getYear())) {
            em.refresh(exercicio, LockModeType.PESSIMISTIC_READ);
            if (exercicio.getStatus() == StatusExercicio.ENCERRADO) {
                throw new IllegalStateException("A disponibilidade para uso afeta um exercício encerrado.");
            }
        }
        if (periodos.existsByEmpresaIdAndStatusAndDataInicioBetween(empresa, StatusPeriodo.ENCERRADO,
                inicio.atDay(1), fim.atDay(1))) {
            throw new IllegalStateException("A disponibilidade para uso afeta uma competência encerrada.");
        }
        if (base.signum() > 0 && periodos.existeLacunaAteUltimoEncerramento(empresa, inicio.atDay(1), fim.atDay(1))) {
            throw new IllegalStateException("A disponibilidade para uso afeta uma lacuna anterior a uma competência encerrada.");
        }
    }

    private BigDecimal validarDadosPatrimoniais(LocalDate dataAquisicao, LocalDate disponibilidade, boolean depreciavel,
                                                 Integer vidaUtil, UUID despesa, BigDecimal residual, BigDecimal custo) {
        if (disponibilidade != null && disponibilidade.isBefore(dataAquisicao)) {
            throw new IllegalArgumentException("A disponibilidade para uso não pode anteceder a aquisição.");
        }
        if (residual == null || residual.signum() < 0 || residual.compareTo(custo) > 0) {
            throw new IllegalArgumentException("O valor residual deve estar entre zero e o custo de aquisição.");
        }
        if (depreciavel) {
            if (vidaUtil == null || vidaUtil <= 0) {
                throw new IllegalArgumentException("Informe vida útil positiva em meses para o ativo depreciável.");
            }
        } else if (vidaUtil != null || despesa != null) {
            throw new IllegalArgumentException("Vida útil e conta de despesa são aplicáveis somente a ativos depreciáveis.");
        }
        return residual.signum() == 0 ? BigDecimal.ZERO.setScale(2) : PartidaContabil.normalizarValor(residual);
    }

    private ContaDoPlano validarContaPatrimonial(UUID empresa, UUID contaId, boolean depreciavel, List<ContaDoPlano> estrutura) {
        UUID contaImobilizado = contas.buscarContaDoModeloPadrao(empresa, IMOBILIZADO)
                .orElseThrow(() -> new IllegalStateException("Grupo Imobilizado ausente no plano da empresa."));
        ContaDoPlano conta = estrutura.stream().filter(c -> c.getId().equals(contaId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Conta patrimonial não encontrada no plano."));
        if (!contaImobilizado.equals(conta.getContaPaiId()) || conta.getTipo() != TipoConta.ANALITICA
                || conta.getGrupo() != GrupoConta.ATIVO || conta.getNatureza() != NaturezaConta.DEVEDORA
                || conta.getContaRedutora() || conta.getClassificacaoBP() != ClassificacaoBP.ATIVO_NAO_CIRCULANTE) {
            throw new IllegalArgumentException("A conta patrimonial deve ser analítica devedora do Imobilizado.");
        }
        if (!depreciavel) {
            validarReferencia(empresa, conta.getId(), List.of(TERRENOS),
                    "Terrenos é a classe não depreciável do Imobilizado no modelo padrão.");
        }
        return conta;
    }

    private UUID resolverRedutora(List<ContaDoPlano> estrutura, UUID patrimonial) {
        List<ContaDoPlano> reduzidas = estrutura.stream()
                .filter(c -> patrimonial.equals(c.getContaReduzidaId())).toList();
        if (reduzidas.size() != 1) {
            throw new IllegalArgumentException("A conta patrimonial deve possuir uma única redutora de depreciação vinculada.");
        }
        ContaDoPlano redutora = reduzidas.getFirst();
        if (!redutora.getContaRedutora() || redutora.getGrupo() != GrupoConta.ATIVO
                || redutora.getNatureza() != NaturezaConta.CREDORA
                || redutora.getClassificacaoBP() != ClassificacaoBP.ATIVO_NAO_CIRCULANTE) {
            throw new IllegalArgumentException("Conta redutora de depreciação incompatível com o ativo.");
        }
        return redutora.getId();
    }

    private void validarDespesa(UUID empresa, UUID contaId, List<ContaDoPlano> estrutura) {
        String mensagem = "Informe uma conta de despesa de depreciação compatível com a finalidade do ativo.";
        validarReferencia(empresa, contaId, DESPESAS_DEPRECIACAO, mensagem);
        ContaDoPlano despesa = estrutura.stream().filter(c -> c.getId().equals(contaId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(mensagem));
        if (despesa.getGrupo() != GrupoConta.DESPESA || despesa.getNatureza() != NaturezaConta.DEVEDORA
                || despesa.getContaRedutora() || despesa.getClassificacaoDRE() != ClassificacaoDRE.DESPESAS_OPERACIONAIS) {
            throw new IllegalArgumentException(mensagem);
        }
    }

    private void validarReferencia(UUID empresa, UUID selecionada, List<String> referencias, String mensagem) {
        if (selecionada == null || referencias.stream().map(codigo -> contas.buscarContaDoModeloPadrao(empresa, codigo))
                .noneMatch(id -> id.filter(selecionada::equals).isPresent())) {
            throw new IllegalArgumentException(mensagem);
        }
    }

    private String textoObrigatorio(String valor, int limite, String campo) {
        String texto = Objects.toString(valor, "").trim();
        if (texto.isBlank() || texto.length() > limite) {
            throw new IllegalArgumentException(campo + " deve ter entre 1 e " + limite + " caracteres.");
        }
        return texto;
    }

    private FichaAtivo ficha(AtivoPatrimonial ativo, List<ApropriacaoDepreciacao> historico) {
        LancamentoContabil abertura = ativo.getLancamentoAbertura();
        LancamentoContabil lancamento = ativo.isImportado() ? abertura : ativo.getLancamentoAquisicao();
        LancamentoContabil estorno = ativo.isImportado() ? null : contabilidade.buscarEstornoEfetivo(lancamento);
        ReversaoOperacional reversao = estorno == null ? null : LancamentoContabilService.referenciaEstorno(
                ativo.getId(), estorno, estorno.getData(), estorno.getConfirmadoPor().getId());
        FichaAtivo.Situacao situacao = ativo.getDataDisponibilidade() == null
                ? FichaAtivo.Situacao.AGUARDANDO_DISPONIBILIDADE : FichaAtivo.Situacao.DISPONIBILIDADE_REGISTRADA;
        if (reversao != null) {
            situacao = FichaAtivo.Situacao.AQUISICAO_ESTORNADA;
        }
        var efetivas = historico.stream().filter(a -> a.reversao() == null).toList();
        BigDecimal apropriadaLex = efetivas.stream().map(ApropriacaoDepreciacao::valor)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal acumulada = ativo.getDepreciacaoInicial().add(apropriadaLex);
        int mesesConsumidos = ativo.getMesesConsumidosInicio() + efetivas.size();
        int mesesRestantes = reversao == null ? ativo.vidaUtilInicialLex() - efetivas.size() : 0;
        return new FichaAtivo(ativo.getId(), ativo.getEmpresaId(), ativo.getUsuarioId(), ativo.getCodigo(),
                ativo.getDescricao(), ativo.getContaPatrimonialId(), ativo.getDataAquisicao(), ativo.getDataDisponibilidade(),
                ativo.getCustoAquisicao(), ativo.isDepreciavel(), ativo.getValorResidual(), ativo.getVidaUtilMeses(),
                ativo.baseDepreciavel(), ativo.getContaRedutoraId(), ativo.getContaDespesaId(), situacao,
                lancamento.getId(), ativo.isImportado() ? null : lancamento.getExercicioId(), lancamento.getNumero(), acumulada,
                reversao == null ? ativo.getCustoAquisicao().subtract(acumulada) : ZERO,
                reversao == null ? ativo.baseDepreciavel().subtract(acumulada) : ZERO,
                mesesConsumidos, mesesRestantes, historico,
                ativo.isImportado() ? FichaAtivo.Origem.IMPORTACAO : FichaAtivo.Origem.AQUISICAO,
                abertura == null ? null : abertura.getId(), abertura == null ? null : abertura.getExercicioId(),
                abertura == null ? null : abertura.getData(),
                abertura == null ? null : YearMonth.from(ativo.getInicioDepreciacaoLex()),
                ativo.primeiraCompetenciaLex(), ativo.getMesesConsumidosInicio(), ativo.getDepreciacaoInicial(),
                apropriadaLex, reversao);
    }

    private void auditarImportacao(AtivoPatrimonial ativo, LancamentoContabil abertura) {
        jdbc.update("""
                insert into auditoria_evento (empresa_id, usuario_id, entidade, entidade_id, acao, dados_novos)
                values (?, ?, 'ativo_patrimonial', ?, 'IMPORTAR_SALDO_INICIAL', jsonb_build_object(
                    'codigo', cast(? as text), 'aberturaId', cast(? as uuid), 'numeroContabil', cast(? as bigint),
                    'dataAquisicao', cast(? as date), 'dataDisponibilidade', cast(? as date),
                    'custoHistorico', cast(? as numeric), 'valorResidual', cast(? as numeric),
                    'depreciacaoImportada', cast(? as numeric), 'vidaUtilMeses', cast(? as integer),
                    'mesesConsumidos', cast(? as integer), 'marcoLex', cast(? as date),
                    'contaPatrimonialId', cast(? as uuid), 'contaRedutoraId', cast(? as uuid),
                    'contaDespesaId', cast(? as uuid)))
                """, ativo.getEmpresaId(), ativo.getUsuarioId(), ativo.getId(), ativo.getCodigo(), abertura.getId(),
                abertura.getNumero(), ativo.getDataAquisicao(), ativo.getDataDisponibilidade(), ativo.getCustoAquisicao(),
                ativo.getValorResidual(), ativo.getDepreciacaoInicial(), ativo.getVidaUtilMeses(),
                ativo.getMesesConsumidosInicio(), ativo.getInicioDepreciacaoLex(), ativo.getContaPatrimonialId(),
                ativo.getContaRedutoraId(), ativo.getContaDespesaId());
    }

    private void auditar(AtivoPatrimonial ativo) {
        LancamentoContabil lancamento = ativo.getLancamentoAquisicao();
        jdbc.update("""
                insert into auditoria_evento (empresa_id, usuario_id, entidade, entidade_id, acao, dados_novos)
                values (?, ?, 'ativo_patrimonial', ?, 'AQUISICAO', jsonb_build_object(
                    'codigo', cast(? as text), 'lancamentoId', cast(? as uuid), 'numeroContabil', cast(? as bigint),
                    'dataAquisicao', cast(? as date), 'dataDisponibilidade', cast(? as date),
                    'custoAquisicao', cast(? as numeric), 'valorResidual', cast(? as numeric),
                    'vidaUtilMeses', cast(? as integer), 'depreciavel', cast(? as boolean)))
                """, ativo.getEmpresaId(), ativo.getUsuarioId(), ativo.getId(), ativo.getCodigo(), lancamento.getId(),
                lancamento.getNumero(), ativo.getDataAquisicao(), ativo.getDataDisponibilidade(), ativo.getCustoAquisicao(),
                ativo.getValorResidual(), ativo.getVidaUtilMeses(), ativo.isDepreciavel());
    }
}
