package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.AquisicaoAtivo;
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
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.AtivoPatrimonialRepository;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.ContaContabilRepository.ContaDoPlano;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AtivoPatrimonialService {

    // Referências do modelo padrão V1, provisionado pela V3.
    private static final String IMOBILIZADO = "1.2.03";
    private static final String TERRENOS = "1.2.03.001";
    private static final List<String> CONTRAPARTIDAS = List.of("1.1.01.001", "1.1.01.002", "2.1.01.001", "2.2.02.001");
    private static final List<String> DESPESAS_DEPRECIACAO = List.of("6.1.005", "6.2.006");

    private final AtivoPatrimonialRepository ativos;
    private final PlanoContasRepository planos;
    private final ContaContabilRepository contas;
    private final LancamentoContabilService contabilidade;
    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FichaAtivo registrarAquisicao(AquisicaoAtivo aquisicao) {
        if (aquisicao == null) {
            throw new IllegalArgumentException("Informe os dados da aquisição patrimonial.");
        }
        if (!"read committed".equals(jdbc.queryForObject("show transaction_isolation", String.class))) {
            throw new IllegalStateException("A aquisição patrimonial exige READ COMMITTED.");
        }
        UUID empresa = aquisicao.empresaId();
        UUID usuario = aquisicao.usuarioId();
        contabilidade.validarDestinoOperacional(empresa, usuario, aquisicao.exercicioId(), aquisicao.periodoId(),
                aquisicao.dataAquisicao());
        BigDecimal custo = PartidaContabil.normalizarValor(aquisicao.custoAquisicao());
        BigDecimal residual = validarDadosPatrimoniais(aquisicao, custo);
        String codigo = textoObrigatorio(Objects.toString(aquisicao.codigo(), "").toUpperCase(Locale.ROOT),
                50, "Código patrimonial");
        String descricao = textoObrigatorio(aquisicao.descricao(), 200, "Descrição");
        UUID plano = planos.findByEmpresaId(empresa)
                .orElseThrow(() -> new IllegalStateException("A empresa não possui plano de contas.")).getId();
        List<ContaDoPlano> estrutura = contas.buscarEstruturaDoPlano(empresa);
        ContaDoPlano patrimonial = validarContaPatrimonial(aquisicao, estrutura);
        UUID redutora = aquisicao.depreciavel() ? resolverRedutora(estrutura, patrimonial.getId()) : null;
        validarReferencia(empresa, aquisicao.contrapartidaId(), CONTRAPARTIDAS, "Conta de contrapartida incompatível.");
        if (aquisicao.depreciavel()) {
            validarDespesa(aquisicao, estrutura);
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
        return ficha(ativo);
    }

    @Transactional(readOnly = true)
    public FichaAtivo consultar(UUID empresaId, UUID usuarioId, UUID ativoId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        return ativos.findByIdAndEmpresaId(ativoId, empresaId).map(this::ficha)
                .orElseThrow(() -> new IllegalArgumentException("Ativo patrimonial não encontrado na empresa."));
    }

    @Transactional(readOnly = true)
    public List<FichaAtivo> listar(UUID empresaId, UUID usuarioId) {
        contabilidade.validarAcessoOperacional(empresaId, usuarioId);
        return ativos.findAllByEmpresaIdOrderByCodigoAsc(empresaId).stream().map(this::ficha).toList();
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

    private ContaDoPlano validarContaPatrimonial(AquisicaoAtivo aquisicao, List<ContaDoPlano> estrutura) {
        UUID empresa = aquisicao.empresaId();
        UUID contaImobilizado = contas.buscarContaDoModeloPadrao(empresa, IMOBILIZADO)
                .orElseThrow(() -> new IllegalStateException("Grupo Imobilizado ausente no plano da empresa."));
        ContaDoPlano conta = estrutura.stream().filter(c -> c.getId().equals(aquisicao.contaPatrimonialId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Conta patrimonial não encontrada no plano."));
        if (!contaImobilizado.equals(conta.getContaPaiId()) || conta.getTipo() != TipoConta.ANALITICA
                || conta.getGrupo() != GrupoConta.ATIVO || conta.getNatureza() != NaturezaConta.DEVEDORA
                || conta.getContaRedutora() || conta.getClassificacaoBP() != ClassificacaoBP.ATIVO_NAO_CIRCULANTE) {
            throw new IllegalArgumentException("A conta patrimonial deve ser analítica devedora do Imobilizado.");
        }
        if (!aquisicao.depreciavel()) {
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

    private void validarDespesa(AquisicaoAtivo aquisicao, List<ContaDoPlano> estrutura) {
        String mensagem = "Informe uma conta de despesa de depreciação compatível com a finalidade do ativo.";
        validarReferencia(aquisicao.empresaId(), aquisicao.contaDespesaId(), DESPESAS_DEPRECIACAO, mensagem);
        ContaDoPlano despesa = estrutura.stream().filter(c -> c.getId().equals(aquisicao.contaDespesaId()))
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

    private FichaAtivo ficha(AtivoPatrimonial ativo) {
        LancamentoContabil lancamento = ativo.getLancamentoAquisicao();
        FichaAtivo.Situacao situacao = ativo.getDataDisponibilidade() == null
                ? FichaAtivo.Situacao.AGUARDANDO_DISPONIBILIDADE : FichaAtivo.Situacao.DISPONIBILIDADE_REGISTRADA;
        return new FichaAtivo(ativo.getId(), ativo.getEmpresaId(), ativo.getUsuarioId(), ativo.getCodigo(),
                ativo.getDescricao(), ativo.getContaPatrimonialId(), ativo.getDataAquisicao(), ativo.getDataDisponibilidade(),
                ativo.getCustoAquisicao(), ativo.isDepreciavel(), ativo.getValorResidual(), ativo.getVidaUtilMeses(),
                ativo.baseDepreciavel(), ativo.getContaRedutoraId(), ativo.getContaDespesaId(), situacao,
                lancamento.getId(), lancamento.getExercicioId(), lancamento.getNumero());
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
