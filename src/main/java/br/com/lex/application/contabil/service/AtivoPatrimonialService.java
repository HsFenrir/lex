package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.AquisicaoAtivo;
import br.com.lex.application.contabil.dto.ApropriacaoDepreciacao;
import br.com.lex.application.contabil.dto.FichaAtivo;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;

@Service
@RequiredArgsConstructor
public class AtivoPatrimonialService {

    // Referências do modelo padrão V1, provisionado pela V3.
    private static final String IMOBILIZADO = "1.2.03";
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
        BigDecimal residual = validarDadosPatrimoniais(aquisicao, custo);
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FichaAtivo registrarDisponibilidade(UUID empresaId, UUID usuarioId, UUID exercicioId, UUID periodoId,
                                               UUID ativoId, LocalDate data) {
        validarIsolamento();
        contabilidade.validarDestinoOperacional(empresaId, usuarioId, exercicioId, periodoId, data);
        AtivoPatrimonial ativo = buscarAtivo(empresaId, ativoId);
        validarCompetenciasDaDisponibilidade(empresaId, data.getYear(), data, ativo.baseDepreciavel(),
                ativo.getVidaUtilMeses());
        em.refresh(ativo, LockModeType.PESSIMISTIC_WRITE);
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

    private BigDecimal validarDadosPatrimoniais(AquisicaoAtivo aquisicao, BigDecimal custo) {
        if (aquisicao.dataDisponibilidade() != null && aquisicao.dataDisponibilidade().isBefore(aquisicao.dataAquisicao())) {
            throw new IllegalArgumentException("A disponibilidade para uso não pode anteceder a aquisição.");
        }
        BigDecimal residual = aquisicao.valorResidual();
        if (residual == null || residual.signum() < 0 || residual.compareTo(custo) > 0) {
            throw new IllegalArgumentException("O valor residual deve estar entre zero e o custo de aquisição.");
        }
        if (aquisicao.depreciavel()) {
            if (aquisicao.vidaUtilMeses() == null || aquisicao.vidaUtilMeses() <= 0) {
                throw new IllegalArgumentException("Informe vida útil positiva em meses para o ativo depreciável.");
            }
        } else if (aquisicao.vidaUtilMeses() != null || aquisicao.contaDespesaId() != null) {
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
        LancamentoContabil lancamento = ativo.getLancamentoAquisicao();
        FichaAtivo.Situacao situacao = ativo.getDataDisponibilidade() == null
                ? FichaAtivo.Situacao.AGUARDANDO_DISPONIBILIDADE : FichaAtivo.Situacao.DISPONIBILIDADE_REGISTRADA;
        BigDecimal acumulada = historico.stream().map(ApropriacaoDepreciacao::valor)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        int mesesConsumidos = historico.size();
        int mesesRestantes = ativo.isDepreciavel() ? ativo.getVidaUtilMeses() - mesesConsumidos : 0;
        return new FichaAtivo(ativo.getId(), ativo.getEmpresaId(), ativo.getUsuarioId(), ativo.getCodigo(),
                ativo.getDescricao(), ativo.getContaPatrimonialId(), ativo.getDataAquisicao(), ativo.getDataDisponibilidade(),
                ativo.getCustoAquisicao(), ativo.isDepreciavel(), ativo.getValorResidual(), ativo.getVidaUtilMeses(),
                ativo.baseDepreciavel(), ativo.getContaRedutoraId(), ativo.getContaDespesaId(), situacao,
                lancamento.getId(), lancamento.getExercicioId(), lancamento.getNumero(), acumulada,
                ativo.getCustoAquisicao().subtract(acumulada), ativo.baseDepreciavel().subtract(acumulada),
                mesesConsumidos, mesesRestantes, historico);
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
