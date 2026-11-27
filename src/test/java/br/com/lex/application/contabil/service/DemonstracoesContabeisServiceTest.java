package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.BalancoPatrimonial;
import br.com.lex.application.contabil.dto.Dre;
import br.com.lex.application.contabil.dto.Dre.Rubrica;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.ClassificacaoDRE;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DemonstracoesContabeisServiceTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 1, 1);
    private static final LocalDate JANEIRO = INICIO.plusDays(14);
    private static final LocalDate FEVEREIRO = JANEIRO.plusMonths(1);

    private static final String CAIXA = "1.1.01.001";
    private static final String EDIFICACOES = "1.2.03.002";
    private static final String DEPRECIACAO_ACUMULADA = "1.2.03.101";
    private static final String FORNECEDORES = "2.1.01.001";
    private static final String EMPRESTIMOS = "2.2.01.001";
    private static final String CAPITAL = "3.1.001";
    private static final String RECEITA = "4.1.001";
    private static final String DEDUCAO = "4.2.001";
    private static final String RECEITA_FINANCEIRA = "4.3.002";
    private static final String OUTRA_RECEITA = "4.4.002";
    private static final String CMV = "5.1.001";
    private static final String DESPESA = "6.2.002";
    private static final String DESPESA_FINANCEIRA = "6.3.001";
    private static final String IRPJ = "6.4.001";
    private static final String OUTRA_DESPESA = "6.5.002";

    @Autowired private BalancoPatrimonialService bp;
    @Autowired private DreService dre;
    @Autowired private BalanceteService balancete;
    @Autowired private LivroRazaoService razao;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private PlanoContasService planos;
    @Autowired private ContaContabilRepository contas;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private Cenario dados;

    @BeforeEach
    void preparar() {
        dados = criarCenario();
    }

    @Test
    void bpComAberturaRedutoraAgregacaoEResultadoRemanescenteNasDatasContabeis() {
        abrir(INICIO, debito(CAIXA, "200.00"), debito(EDIFICACOES, "300.00"),
                credito(DEPRECIACAO_ACUMULADA, "50.00"), credito(FORNECEDORES, "80.00"),
                credito(EMPRESTIMOS, "40.00"), credito(CAPITAL, "330.00"));
        UUID venda = confirmar(JANEIRO, CAIXA, RECEITA, "100.00");
        confirmar(JANEIRO, DESPESA, CAIXA, "30.00");
        confirmar(JANEIRO, FORNECEDORES, CAIXA, "90.00");
        rascunho(JANEIRO, CAIXA, RECEITA, "999.00");
        estornar(venda, FEVEREIRO);
        jdbc.update("update conta_contabil set ativa = false where id = ?", conta(DEPRECIACAO_ACUMULADA));
        jdbc.update("update exercicio_contabil set status = 'ENCERRADO' where id = ?", dados.exercicio());
        em.clear();

        BalancoPatrimonial janeiro = emitirBP(JANEIRO);
        assertThat(janeiro.ativo()).isEqualByComparingTo("430.00");
        assertThat(janeiro.passivo()).isEqualByComparingTo("30.00");
        assertThat(janeiro.patrimonioLiquidoEscriturado()).isEqualByComparingTo("330.00");
        assertThat(janeiro.resultadoNaoEncerrado()).isEqualByComparingTo("70.00");
        assertThat(janeiro.patrimonioLiquido()).isEqualByComparingTo("400.00");
        assertThat(janeiro.diferencaPatrimonial()).isEqualByComparingTo("0");
        assertThat(janeiro.equilibrado()).isTrue();

        BalancoPatrimonial.Secao imobilizado = secao(janeiro, ClassificacaoBP.ATIVO_NAO_CIRCULANTE);
        assertThat(imobilizado.total()).isEqualByComparingTo("250.00");
        assertThat(linha(imobilizado, "1.2.03").valorApresentado()).isEqualByComparingTo("250.00");
        BalancoPatrimonial.Linha redutora = linha(imobilizado, DEPRECIACAO_ACUMULADA);
        assertThat(redutora.conta().contaReduzidaId()).isEqualTo(conta(EDIFICACOES));
        assertThat(redutora.conta().ativa()).isFalse();
        assertThat(redutora.saldoContabil()).isEqualByComparingTo("-50.00");
        assertThat(redutora.valorApresentado()).isEqualByComparingTo("-50.00");
        BalancoPatrimonial.Linha fornecedor = linha(secao(janeiro, ClassificacaoBP.PASSIVO_CIRCULANTE), FORNECEDORES);
        assertThat(fornecedor.saldoContabil()).isEqualByComparingTo("10.00");
        assertThat(fornecedor.valorApresentado()).isEqualByComparingTo("-10.00");

        BalancoPatrimonial fevereiro = emitirBP(FEVEREIRO);
        assertThat(fevereiro.resultadoNaoEncerrado()).isEqualByComparingTo("-30.00");
        assertThat(fevereiro.patrimonioLiquido()).isEqualByComparingTo("300.00");
        assertThat(fevereiro.ativo()).isEqualByComparingTo("330.00");
        assertThat(fevereiro.equilibrado()).isTrue();
        for (BalancoPatrimonial.Secao secao : janeiro.secoes()) {
            for (BalancoPatrimonial.Linha linha : secao.linhas()) {
                if (linha.conta().tipo() == TipoConta.ANALITICA) {
                    assertThat(linha.saldoContabil()).isEqualByComparingTo(razao.emitir(dados.empresa(), dados.usuario(),
                            dados.exercicio(), linha.conta().id(), JANEIRO, JANEIRO).saldos().saldoFinal().valor());
                }
            }
        }
    }

    @Test
    void dreVerticalPreservaSinaisEReconciliaComBalancete() {
        confirmar(JANEIRO, CAIXA, RECEITA, "200.00");
        confirmar(JANEIRO, DEDUCAO, CAIXA, "20.00");
        confirmar(JANEIRO, CMV, CAIXA, "60.00");
        confirmar(JANEIRO, DESPESA, CAIXA, "30.00");
        confirmar(JANEIRO, CAIXA, RECEITA_FINANCEIRA, "7.00");
        confirmar(JANEIRO, DESPESA_FINANCEIRA, CAIXA, "2.00");
        confirmar(JANEIRO, CAIXA, OUTRA_RECEITA, "9.00");
        confirmar(JANEIRO, OUTRA_DESPESA, CAIXA, "4.00");
        confirmar(JANEIRO, IRPJ, CAIXA, "20.00");
        rascunho(JANEIRO, CAIXA, RECEITA, "500.00");

        Dre relatorio = emitirDre(JANEIRO, JANEIRO);
        conferirRubrica(relatorio, Rubrica.RECEITA_BRUTA, "200.00");
        conferirRubrica(relatorio, Rubrica.DEDUCOES_RECEITA, "-20.00");
        conferirRubrica(relatorio, Rubrica.RECEITA_LIQUIDA, "180.00");
        conferirRubrica(relatorio, Rubrica.RESULTADO_BRUTO, "120.00");
        conferirRubrica(relatorio, Rubrica.RESULTADO_OPERACIONAL, "90.00");
        conferirRubrica(relatorio, Rubrica.RESULTADO_FINANCEIRO, "5.00");
        conferirRubrica(relatorio, Rubrica.RESULTADO_ANTES_DOS_TRIBUTOS, "100.00");
        conferirRubrica(relatorio, Rubrica.RESULTADO_LIQUIDO, "80.00");

        BigDecimal resultadoBalancete = balancete.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), JANEIRO, JANEIRO)
                .linhas().stream()
                .filter(l -> l.conta().tipo() == TipoConta.ANALITICA)
                .filter(l -> List.of(GrupoConta.RECEITA, GrupoConta.CUSTO, GrupoConta.DESPESA).contains(l.conta().grupo()))
                .map(l -> l.saldos().totalCreditos().subtract(l.saldos().totalDebitos()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(rubrica(relatorio, Rubrica.RESULTADO_LIQUIDO).valores().efeitoEscrituradoNoIntervalo())
                .isEqualByComparingTo(resultadoBalancete);
        assertThat(emitirBP(JANEIRO).resultadoNaoEncerrado()).isEqualByComparingTo(resultadoBalancete);
    }

    @Test
    void separaResultadoImportadoMovimentoEconomicoEEstornosNoRecorte() {
        UUID abertura = abrir(JANEIRO, debito(CAIXA, "60.00"), debito(DESPESA, "40.00"), credito(RECEITA, "100.00"));
        UUID venda = confirmar(JANEIRO.plusDays(1), CAIXA, RECEITA, "20.00");
        estornar(abertura, FEVEREIRO);
        estornar(venda, FEVEREIRO);

        Dre anterior = emitirDre(INICIO, JANEIRO.minusDays(1));
        assertThat(anterior.dataAberturaComResultado()).isEqualTo(JANEIRO);
        assertThat(anterior.historicoAnteriorAMigracaoSemDetalhamento()).isTrue();
        conferirRubrica(anterior, Rubrica.RESULTADO_LIQUIDO, "0");

        Dre parcial = emitirDre(JANEIRO, JANEIRO.plusDays(1));
        assertThat(parcial.historicoAnteriorAMigracaoSemDetalhamento()).isTrue();
        assertThat(parcial.incluiAberturaNoResultadoDoIntervalo()).isFalse();
        Dre.Linha resultado = rubrica(parcial, Rubrica.RESULTADO_LIQUIDO);
        assertThat(resultado.valores().aberturaImportada()).isEqualByComparingTo("60.00");
        assertThat(resultado.valores().movimentosEconomicos()).isEqualByComparingTo("20.00");
        assertThat(resultado.valores().acumuladoAteFim()).isEqualByComparingTo("80.00");
        assertThat(resultado.valorDemonstrado()).isEqualByComparingTo("20.00");

        Dre acumulada = emitirDre(INICIO, JANEIRO.plusDays(1));
        assertThat(acumulada.incluiAberturaNoResultadoDoIntervalo()).isTrue();
        conferirRubrica(acumulada, Rubrica.RESULTADO_LIQUIDO, "80.00");

        Dre posterior = emitirDre(FEVEREIRO, FEVEREIRO);
        assertThat(posterior.historicoAnteriorAMigracaoSemDetalhamento()).isFalse();
        Dre.Linha reversao = rubrica(posterior, Rubrica.RESULTADO_LIQUIDO);
        assertThat(reversao.valores().estornosDeAbertura()).isEqualByComparingTo("-60.00");
        assertThat(reversao.valores().movimentosEconomicos()).isEqualByComparingTo("-20.00");
        assertThat(reversao.valores().acumuladoAteFim()).isEqualByComparingTo("0.00");
        assertThat(reversao.valorDemonstrado()).isEqualByComparingTo("-80.00");
        assertThat(emitirBP(FEVEREIRO).resultadoNaoEncerrado()).isEqualByComparingTo("0.00");
    }

    @Test
    void herdaClassificacaoDoMesmoGrupoERejeitaAusenciaSemAlternativa() {
        confirmar(JANEIRO, CAIXA, RECEITA, "10.00");
        jdbc.update("update conta_contabil set classificacao_bp = null where id = ?", conta(CAIXA));
        jdbc.update("update conta_contabil set classificacao_dre = null where id = ?", conta(RECEITA));
        assertThat(secao(emitirBP(JANEIRO), ClassificacaoBP.ATIVO_CIRCULANTE).total()).isEqualByComparingTo("10.00");
        assertThat(emitirDre(JANEIRO, JANEIRO).contas()).singleElement()
                .satisfies(c -> assertThat(c.classificacao()).isEqualTo(ClassificacaoDRE.RECEITA_BRUTA));

        jdbc.update("update conta_contabil set classificacao_bp = null where id in (?, ?)",
                conta("1.1"), conta("1.1.01"));
        jdbc.update("update conta_contabil set classificacao_dre = null where id = ?", conta("4.1"));
        assertThat(assertThrows(IllegalStateException.class, () -> emitirBP(JANEIRO)))
                .hasMessageContaining(CAIXA).hasMessageContaining("classificação de BP");
        assertThat(assertThrows(IllegalStateException.class, () -> emitirDre(JANEIRO, JANEIRO)))
                .hasMessageContaining(RECEITA).hasMessageContaining("classificação de DRE");
    }

    @Test
    void emiteSemEscrituracaoERejeitaContextoDeOutraEmpresaOuDatasInvalidas() {
        Cenario outro = criarCenario();
        UUID lancamento = lancamentos.criarRascunho(outro.empresa(), outro.usuario(), outro.exercicio(),
                outro.janeiro(), JANEIRO, "Movimento de outra empresa").getId();
        lancamentos.adicionarPartida(outro.empresa(), outro.usuario(), lancamento, outro.contas().get(CAIXA),
                TipoPartida.DEBITO, BigDecimal.ONE, null);
        lancamentos.adicionarPartida(outro.empresa(), outro.usuario(), lancamento, outro.contas().get(RECEITA),
                TipoPartida.CREDITO, BigDecimal.ONE, null);
        lancamentos.confirmarLancamento(outro.empresa(), outro.usuario(), lancamento);

        assertThat(emitirBP(JANEIRO).ativo()).isEqualByComparingTo("0");
        conferirRubrica(emitirDre(INICIO, JANEIRO), Rubrica.RESULTADO_LIQUIDO, "0");
        assertThrows(IllegalArgumentException.class,
                () -> bp.emitir(dados.empresa(), outro.usuario(), dados.exercicio(), JANEIRO));
        assertThrows(IllegalArgumentException.class,
                () -> dre.emitir(dados.empresa(), outro.usuario(), dados.exercicio(), INICIO, JANEIRO));
        assertThrows(IllegalArgumentException.class,
                () -> bp.emitir(dados.empresa(), dados.usuario(), outro.exercicio(), JANEIRO));
        assertThrows(IllegalArgumentException.class,
                () -> dre.emitir(dados.empresa(), dados.usuario(), outro.exercicio(), INICIO, JANEIRO));
        assertThrows(IllegalArgumentException.class, () -> emitirBP(INICIO.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> emitirDre(JANEIRO, INICIO));
        assertThrows(IllegalArgumentException.class, () -> emitirDre(INICIO, INICIO.plusYears(1)));
    }

    @Test
    void agregaValoresSuperioresAPrecisaoIndividual() {
        String limite = "99999999999999999.99";
        confirmar(JANEIRO, CAIXA, RECEITA, limite);
        confirmar(JANEIRO, CAIXA, RECEITA, limite);

        String total = "199999999999999999.98";
        conferirRubrica(emitirDre(INICIO, JANEIRO), Rubrica.RESULTADO_LIQUIDO, total);
        BalancoPatrimonial balanco = emitirBP(JANEIRO);
        assertThat(balanco.ativo()).isEqualByComparingTo(total);
        assertThat(balanco.patrimonioLiquido()).isEqualByComparingTo(total);
        assertThat(balanco.equilibrado()).isTrue();
    }

    private BalancoPatrimonial emitirBP(LocalDate data) {
        return bp.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), data);
    }

    private Dre emitirDre(LocalDate inicio, LocalDate fim) {
        return dre.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), inicio, fim);
    }

    private BalancoPatrimonial.Secao secao(BalancoPatrimonial balanco, ClassificacaoBP classe) {
        return balanco.secoes().stream().filter(s -> s.classificacao() == classe).findFirst().orElseThrow();
    }

    private BalancoPatrimonial.Linha linha(BalancoPatrimonial.Secao secao, String codigo) {
        return secao.linhas().stream().filter(l -> l.conta().codigo().equals(codigo)).findFirst().orElseThrow();
    }

    private Dre.Linha rubrica(Dre relatorio, Rubrica rubrica) {
        return relatorio.linhas().stream().filter(l -> l.rubrica() == rubrica).findFirst().orElseThrow();
    }

    private void conferirRubrica(Dre relatorio, Rubrica rubrica, String valor) {
        assertThat(rubrica(relatorio, rubrica).valorDemonstrado()).isEqualByComparingTo(valor);
    }

    private UUID abrir(LocalDate data, SaldoPorConta... saldos) {
        return lancamentos.registrarSaldosIniciais(new AberturaContabil(dados.empresa(), dados.usuario(),
                dados.exercicio(), dados.janeiro(), data, "Migração", List.of(saldos))).lancamentoId();
    }

    private SaldoPorConta debito(String codigo, String valor) {
        return new SaldoPorConta(conta(codigo), TipoPartida.DEBITO, new BigDecimal(valor));
    }

    private SaldoPorConta credito(String codigo, String valor) {
        return new SaldoPorConta(conta(codigo), TipoPartida.CREDITO, new BigDecimal(valor));
    }

    private UUID conta(String codigo) {
        return dados.contas().get(codigo);
    }

    private UUID confirmar(LocalDate data, String debito, String credito, String valor) {
        UUID id = rascunho(data, debito, credito, valor);
        return lancamentos.confirmarLancamento(dados.empresa(), dados.usuario(), id).getId();
    }

    private UUID rascunho(LocalDate data, String debito, String credito, String valor) {
        UUID id = lancamentos.criarRascunho(dados.empresa(), dados.usuario(), dados.exercicio(), dados.janeiro(),
                data, "Movimento").getId();
        for (SaldoPorConta saldo : List.of(debito(debito, valor), credito(credito, valor))) {
            lancamentos.adicionarPartida(dados.empresa(), dados.usuario(), id, saldo.contaId(),
                    saldo.tipo(), saldo.valor(), null);
        }
        return id;
    }

    private void estornar(UUID original, LocalDate data) {
        UUID estorno = lancamentos.criarRascunhoEstorno(dados.empresa(), dados.usuario(), original,
                dados.exercicio(), dados.fevereiro(), data, "Estorno").getId();
        lancamentos.confirmarEstorno(dados.empresa(), dados.usuario(), estorno);
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa de teste", "Empresa de teste", null);
        em.persist(empresa);
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) INICIO.getYear(),
                INICIO, INICIO.plusYears(1).minusDays(1));
        em.persist(exercicio);
        UUID janeiro = criarPeriodo(exercicio, YearMonth.from(JANEIRO));
        UUID fevereiro = criarPeriodo(exercicio, YearMonth.from(FEVEREIRO));
        em.flush();

        UUID plano = planos.provisionar(empresa.getId(), usuario.getId()).getId();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), janeiro, fevereiro,
                contas.findAllByPlanoContasIdOrderByCodigoAsc(plano).stream()
                        .collect(toMap(c -> c.getCodigo(), c -> c.getId())));
    }

    private UUID criarPeriodo(ExercicioContabil exercicio, YearMonth mes) {
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) mes.getMonthValue(),
                mes.atDay(1), mes.atEndOfMonth());
        em.persist(periodo);
        return periodo.getId();
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID janeiro, UUID fevereiro,
                           Map<String, UUID> contas) {
    }
}
