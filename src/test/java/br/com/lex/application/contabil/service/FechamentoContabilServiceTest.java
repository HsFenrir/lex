package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.Dre;
import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.GrupoConta;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FechamentoContabilServiceTest {

    private static final LocalDate JANEIRO = LocalDate.of(2026, 1, 10);
    private static final LocalDate DEZEMBRO = LocalDate.of(2026, 12, 31);
    private static final LocalDate ANO_SEGUINTE = LocalDate.of(2027, 1, 1);
    private static final String CAIXA = "1.1.01.001";
    private static final String EDIFICACOES = "1.2.03.002";
    private static final String DEPRECIACAO = "1.2.03.101";
    private static final String FORNECEDORES = "2.1.01.001";
    private static final String CAPITAL = "3.1.001";
    private static final String LUCROS = "3.3.001";
    private static final String PREJUIZOS = "3.3.002";
    private static final String RECEITA = "4.1.001";
    private static final String DESPESA = "6.2.002";

    @Autowired private FechamentoContabilService fechamento;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private PlanoContasService planos;
    @Autowired private ContaContabilRepository contas;
    @Autowired private BalanceteService balancete;
    @Autowired private BalancoPatrimonialService bp;
    @Autowired private DreService dre;
    @Autowired private LivroDiarioService diario;
    @Autowired private LivroRazaoService razao;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transacoes;

    @Test
    @Transactional
    void fechaPeriodoSemTransferirResultadoEPreservaRegrasDeAcessoECalendario() {
        Cenario c = criarCenario();
        Cenario outra = criarCenario();
        UUID fevereiro = criarPeriodo(em.getReference(ExercicioContabil.class, c.calendario().exercicio()),
                YearMonth.of(2026, 2));
        em.flush();
        assertThatThrownBy(() -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.calendario().exercicio(), fevereiro))
                .hasMessageContaining("períodos anteriores");
        UUID pendente = rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "45.00");
        assertThatThrownBy(() -> fecharJaneiro(c)).hasMessageContaining("rascunho");
        assertThatThrownBy(() -> fechamento.encerrarPeriodo(c.empresa(), outra.usuario(), c.calendario().exercicio(),
                c.calendario().janeiro())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.calendario().exercicio(),
                outra.calendario().janeiro())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.calendario().exercicio(),
                c.calendario().dezembro())).hasMessageContaining("reservado");

        confirmar(c, pendente);
        fecharJaneiro(c);
        assertThat(statusPeriodo(c.calendario().janeiro())).isEqualTo("ENCERRADO");
        assertThat(saldo(c, c.calendario(), RECEITA, JANEIRO)).isEqualByComparingTo("-45.00");
        assertThat(saldo(c, c.calendario(), LUCROS, JANEIRO)).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> fecharJaneiro(c)).hasMessageContaining("deve estar aberto");
        assertThatThrownBy(() -> rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "1.00"))
                .hasMessageContaining("período deve estar aberto");

        // Simula um rascunho legado gravado fora do serviço para testar a revalidação na confirmação.
        LancamentoContabil confirmado = em.find(LancamentoContabil.class, pendente);
        LancamentoContabil legado = new LancamentoContabil(confirmado.getEmpresa(), confirmado.getExercicio(),
                confirmado.getPeriodo(), confirmado.getPlanoContas(), confirmado.getCriadoPor(),
                JANEIRO, "Rascunho legado", OrigemLancamento.MANUAL);
        em.persist(legado);
        em.flush();
        assertThatThrownBy(() -> confirmar(c, legado.getId())).hasMessageContaining("período deve estar aberto");
    }

    @Test
    @Transactional
    void encerraLucroETransportaPatrimonioComRedutoraESaldoInvertido() {
        Cenario c = criarCenario();
        lancamentos.registrarSaldosIniciais(new AberturaContabil(c.empresa(), c.usuario(), c.calendario().exercicio(),
                c.calendario().janeiro(), JANEIRO, "Abertura", List.of(
                        partida(c, CAIXA, TipoPartida.DEBITO, "100"),
                        partida(c, EDIFICACOES, TipoPartida.DEBITO, "300"),
                        partida(c, FORNECEDORES, TipoPartida.DEBITO, "20"),
                        partida(c, DEPRECIACAO, TipoPartida.CREDITO, "50"),
                        partida(c, CAPITAL, TipoPartida.CREDITO, "370"))));
        confirmar(c, rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "120.00"));
        confirmar(c, rascunho(c, c.calendario(), JANEIRO, DESPESA, CAIXA, "30.00"));
        fecharJaneiro(c);
        completarAnoDaFixture(c);
        var antes = bp.emitir(c.empresa(), c.usuario(), c.calendario().exercicio(), DEZEMBRO);
        Dre dreAntes = emitirDre(c);
        assertThat(antes.resultadoNaoEncerrado()).isEqualByComparingTo("90");

        var resultado = fecharAno(c);
        assertThat(resultado.resultado()).isEqualByComparingTo("90");
        assertThat(resultado.exercicioOrigemId()).isEqualTo(c.calendario().exercicio());
        assertThat(statusPeriodo(c.calendario().dezembro())).isEqualTo("ENCERRADO");
        assertThat(em.find(ExercicioContabil.class, resultado.exercicioDestinoId()).getExercicioOrigemId())
                .isEqualTo(resultado.exercicioOrigemId());
        assertThat(jdbc.queryForObject("select count(*) from periodo_contabil where exercicio_id = ?",
                Long.class, resultado.exercicioDestinoId())).isEqualTo(12L);
        var origem = balancete.emitir(c.empresa(), c.usuario(), resultado.exercicioOrigemId(), JANEIRO, DEZEMBRO);
        var destino = balancete.emitir(c.empresa(), c.usuario(), resultado.exercicioDestinoId(), ANO_SEGUINTE, ANO_SEGUINTE);
        Map<UUID, BigDecimal> saldosDestino = destino.linhas().stream()
                .collect(toMap(l -> l.conta().id(), l -> l.saldos().saldoFinal().valor()));
        origem.linhas().stream().filter(l -> l.conta().tipo() == TipoConta.ANALITICA).forEach(l -> {
            BigDecimal saldo = l.saldos().saldoFinal().valor();
            assertThat(saldosDestino.get(l.conta().id())).isEqualByComparingTo(saldo);
            if (List.of(GrupoConta.RECEITA, GrupoConta.CUSTO, GrupoConta.DESPESA).contains(l.conta().grupo())) {
                assertThat(saldo).isEqualByComparingTo("0");
            }
        });
        assertThat(saldosDestino.get(c.contas().get(DEPRECIACAO))).isEqualByComparingTo("-50");
        assertThat(saldosDestino.get(c.contas().get(FORNECEDORES))).isEqualByComparingTo("20");
        assertThat(saldosDestino.get(c.contas().get(LUCROS))).isEqualByComparingTo("-90");

        var depois = bp.emitir(c.empresa(), c.usuario(), c.calendario().exercicio(), DEZEMBRO);
        assertThat(depois.equilibrado()).isTrue();
        assertThat(depois.patrimonioLiquido()).isEqualByComparingTo(antes.patrimonioLiquido());
        assertThat(depois.resultadoNaoEncerrado()).isEqualByComparingTo("0");
        assertThat(emitirDre(c)).isEqualTo(dreAntes);
        assertThat(diario.emitir(c.empresa(), c.usuario(), resultado.exercicioOrigemId(), DEZEMBRO, DEZEMBRO)
                .lancamentos()).singleElement().satisfies(l -> {
                    assertThat(l.origem()).isEqualTo(OrigemLancamento.ENCERRAMENTO);
                    assertThat(l.referencia().id()).isEqualTo(resultado.lancamentoEncerramentoId());
                });
        assertThat(diario.emitir(c.empresa(), c.usuario(), resultado.exercicioDestinoId(), ANO_SEGUINTE, ANO_SEGUINTE)
                .lancamentos()).singleElement().satisfies(l -> {
                    assertThat(l.origem()).isEqualTo(OrigemLancamento.ABERTURA);
                    assertThat(l.referencia().numero()).isEqualTo(1);
                    assertThat(l.referencia().id()).isEqualTo(resultado.lancamentoAberturaId());
                });
        assertThat(razao.emitir(c.empresa(), c.usuario(), resultado.exercicioOrigemId(), c.contas().get(RECEITA),
                DEZEMBRO, DEZEMBRO).movimentos()).singleElement()
                .satisfies(m -> assertThat(m.origem()).isEqualTo(OrigemLancamento.ENCERRAMENTO));
        assertThat(jdbc.queryForObject("""
                select count(*) from auditoria_evento where empresa_id = ?
                and acao in ('ENCERRAR_PERIODO', 'ENCERRAR_EXERCICIO', 'TRANSPORTAR_SALDOS')
                """, Long.class, c.empresa())).isEqualTo(3L);

        UUID janeiroDestino = jdbc.queryForObject("select id from periodo_contabil where exercicio_id = ? and mes = 1",
                UUID.class, resultado.exercicioDestinoId());
        assertThatThrownBy(() -> lancamentos.criarRascunhoEstorno(c.empresa(), c.usuario(),
                resultado.lancamentoEncerramentoId(), resultado.exercicioDestinoId(), janeiroDestino,
                ANO_SEGUINTE, "Estorno indevido")).hasMessageContaining("encerramento não pode ser estornado");
        assertThatThrownBy(() -> fecharAno(c)).hasMessageContaining("exercício deve estar aberto");
    }

    @Test
    @Transactional
    void transferePrejuizoComPartidasAcimaDoLimiteAgregado() {
        Cenario c = criarCenario();
        String limite = "99999999999999999.99";
        confirmar(c, rascunho(c, c.calendario(), JANEIRO, DESPESA, CAIXA, limite));
        confirmar(c, rascunho(c, c.calendario(), JANEIRO, DESPESA, CAIXA, limite));
        fecharJaneiro(c);
        completarAnoDaFixture(c);
        var encerrado = fecharAno(c);
        assertThat(encerrado.resultado()).isEqualByComparingTo("-199999999999999999.98");
        assertThat(saldo(c, c.calendario(), PREJUIZOS, DEZEMBRO)).isEqualByComparingTo("199999999999999999.98");
        assertThat(saldo(c, c.calendario(), DESPESA, DEZEMBRO)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from partida_contabil where lancamento_id = ?",
                Long.class, encerrado.lancamentoEncerramentoId())).isEqualTo(4L);
        assertThat(jdbc.queryForObject("select count(*) from partida_contabil where lancamento_id = ?",
                Long.class, encerrado.lancamentoAberturaId())).isEqualTo(4L);
        assertThat(bp.emitir(c.empresa(), c.usuario(), c.calendario().exercicio(), DEZEMBRO).equilibrado()).isTrue();
    }

    @Test
    @Transactional
    void zeraContasComResultadoLiquidoZeroEFechaExercicioVazioSemLancamentosFicticios() {
        Cenario c = criarCenario();
        confirmar(c, rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "20.00"));
        confirmar(c, rascunho(c, c.calendario(), JANEIRO, DESPESA, CAIXA, "20.00"));
        fecharJaneiro(c);
        completarAnoDaFixture(c);
        var resultado = fecharAno(c);
        assertThat(resultado.resultado()).isEqualByComparingTo("0");
        assertThat(resultado.lancamentoAberturaId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from partida_contabil where lancamento_id = ?",
                Long.class, resultado.lancamentoEncerramentoId())).isEqualTo(2L);
        assertThat(saldo(c, c.calendario(), RECEITA, DEZEMBRO)).isEqualByComparingTo("0");
        assertThat(saldo(c, c.calendario(), DESPESA, DEZEMBRO)).isEqualByComparingTo("0");

        Cenario vazio = criarCenario();
        fecharJaneiro(vazio);
        completarAnoDaFixture(vazio);
        var semPartidas = fecharAno(vazio);
        assertThat(semPartidas.lancamentoEncerramentoId()).isNull();
        assertThat(semPartidas.lancamentoAberturaId()).isNull();
        assertThat(em.find(ExercicioContabil.class, semPartidas.exercicioDestinoId()).getExercicioOrigemId())
                .isEqualTo(vazio.calendario().exercicio());
    }

    @Test
    @Transactional
    void rejeitaPendenciasClassificacaoIndefinidaEContaInativa() {
        Cenario c = criarCenario();
        fecharJaneiro(c);
        completarAnoDaFixture(c);
        UUID pendente = rascunho(c, c.calendario(), DEZEMBRO, CAIXA, RECEITA, "10.00");
        assertThatThrownBy(() -> fecharAno(c)).hasMessageContaining("rascunho");
        confirmar(c, pendente);
        jdbc.update("update conta_contabil set ativa = false where id = ?", c.contas().get(CAIXA));
        assertThatThrownBy(() -> fecharAno(c)).hasMessageContaining("Conta inativa");
        jdbc.update("update conta_contabil set ativa = true where id = ?", c.contas().get(CAIXA));
        jdbc.update("update conta_contabil set classificacao_dre = null where id in (?, ?)",
                c.contas().get(RECEITA), c.contas().get("4.1"));
        assertThatThrownBy(() -> fecharAno(c)).hasMessageContaining("classificação de DRE");
    }

    @Test
    @Transactional
    void rejeitaDestinoIncompativelSemSubstituirCalendario() {
        Cenario c = criarCenario();
        Calendario destino = criarCalendario(em.getReference(Empresa.class, c.empresa()), (short) 2027);
        jdbc.update("update periodo_contabil set status = 'ENCERRADO' where id = ?", destino.janeiro());
        fecharJaneiro(c);
        completarAnoDaFixture(c);
        assertThatThrownBy(() -> fecharAno(c)).hasMessageContaining("destino está incompatível");
        assertThat(statusPeriodo(destino.janeiro())).isEqualTo("ENCERRADO");
        assertThat(statusPeriodo(c.calendario().dezembro())).isEqualTo("ABERTO");
    }

    @Test
    void aplicaV7SobreV6ComExercicioLegado() {
        comSchema(schema -> {
            migrar(schema, "6");
            executar(schema, () -> {
                UUID usuario = jdbc.queryForObject("""
                        insert into usuario (nome, email, senha_hash)
                        values ('Titular', 'v6@example.test', 'hash-teste') returning id
                        """, UUID.class);
                UUID empresa = jdbc.queryForObject("""
                        insert into empresa (usuario_id, razao_social) values (?, 'Empresa V6') returning id
                        """, UUID.class, usuario);
                jdbc.update("""
                        insert into exercicio_contabil (empresa_id, ano, data_inicio, data_fim)
                        values (?, 2026, DATE '2026-01-01', DATE '2026-12-31')
                        """, empresa);
                return null;
            });
            migrar(schema, null);
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("""
                        select count(*) from exercicio_contabil where ano = 2026 and exercicio_origem_id is null
                        """, Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from flyway_schema_history where version = '7' and success
                        """, Long.class)).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void reverteEncerramentoEAberturaQuandoConfirmacaoDoTransporteFalha() {
        comCenario((schema, c) -> {
            executar(schema, () -> {
                confirmar(c, rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "20.00"));
                fecharJaneiro(c);
                completarAnoDaFixture(c);
                jdbc.execute("""
                        alter table lancamento_contabil add constraint teste_bloquear_transporte
                        check (origem <> 'ABERTURA' or status <> 'CONFIRMADO')
                        """);
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> fecharAno(c)))
                    .isInstanceOf(DataIntegrityViolationException.class).hasStackTraceContaining("teste_bloquear_transporte");
            executar(schema, () -> {
                assertThat(statusPeriodo(c.calendario().dezembro())).isEqualTo("ABERTO");
                assertThat(jdbc.queryForObject("select status from exercicio_contabil where id = ?",
                        String.class, c.calendario().exercicio())).isEqualTo("ABERTO");
                assertThat(jdbc.queryForObject("select count(*) from exercicio_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento", Long.class)).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void fechamentoAguardaConfirmacaoERevalidaRascunhos() {
        comCenario((schema, c) -> {
            UUID manual = executar(schema, () -> rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "50.00"));
            Disputa resultado = disputar(schema, () -> confirmar(c, manual), () -> fecharJaneiro(c));
            assertThat(resultado.erro()).isNull();
            executar(schema, () -> {
                assertThat(statusPeriodo(c.calendario().janeiro())).isEqualTo("ENCERRADO");
                assertThat(saldo(c, c.calendario(), RECEITA, JANEIRO)).isEqualByComparingTo("-50.00");
                return null;
            });
        });
    }

    @Test
    void anualAguardaEstornoNoUltimoPeriodoEApuraAEscrituracaoConfirmada() {
        comCenario((schema, c) -> {
            UUID estorno = executar(schema, () -> {
                UUID original = rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "50.00");
                confirmar(c, original);
                fecharJaneiro(c);
                completarAnoDaFixture(c);
                return lancamentos.criarRascunhoEstorno(c.empresa(), c.usuario(), original,
                        c.calendario().exercicio(), c.calendario().dezembro(), DEZEMBRO, "Estorno").getId();
            });
            Disputa resultado = disputar(schema,
                    () -> lancamentos.confirmarEstorno(c.empresa(), c.usuario(), estorno),
                    () -> fecharAno(c));
            assertThat(resultado.erro()).isNull();
            executar(schema, () -> {
                assertThat(statusPeriodo(c.calendario().dezembro())).isEqualTo("ENCERRADO");
                assertThat(saldo(c, c.calendario(), RECEITA, DEZEMBRO)).isEqualByComparingTo("0");
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from exercicio_contabil where exercicio_origem_id = ?
                        """, Long.class, c.calendario().exercicio())).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void fechamentoImpedeCriacaoConcorrenteDeRascunhoESegundoFechamento() {
        comCenario((schema, c) -> {
            Disputa criacao = disputar(schema, () -> fecharJaneiro(c),
                    () -> rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "50.00"));
            assertThat(criacao.erro()).isInstanceOf(IllegalStateException.class).hasMessageContaining("período deve estar aberto");
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isZero();
                completarAnoDaFixture(c);
                return null;
            });
            Disputa duplicado = disputar(schema, () -> fecharAno(c), () -> fecharAno(c));
            assertThat(duplicado.erro()).isInstanceOf(IllegalStateException.class).hasMessageContaining("exercício deve estar aberto");
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("""
                        select count(*) from exercicio_contabil where exercicio_origem_id is not null
                        """, Long.class)).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void confirmacaoNoSucessorAguardaTransporteEProssegueComNumeroDois() {
        comCenario((schema, c) -> {
            Calendario destino = executar(schema, () -> {
                confirmar(c, rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "20.00"));
                fecharJaneiro(c);
                completarAnoDaFixture(c);
                return criarCalendario(em.getReference(Empresa.class, c.empresa()), (short) 2027);
            });
            UUID manual = executar(schema, () -> rascunho(c, destino, ANO_SEGUINTE, CAIXA, RECEITA, "10.00"));
            assertThatThrownBy(() -> executar(schema, () -> confirmar(c, manual)))
                    .hasMessageContaining("continuidade do exercício anterior");
            Disputa resultado = disputar(schema, () -> fecharAno(c), () -> confirmar(c, manual));
            assertThat(resultado.erro()).isNull();
            executar(schema, () -> {
                assertThat(jdbc.queryForObject("""
                        select numero from lancamento_contabil where id = ? and status = 'CONFIRMADO'
                        """, Long.class, manual)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from lancamento_contabil where exercicio_id = ? and origem = 'ABERTURA'
                        """, Long.class, destino.exercicio())).isEqualTo(1L);
                assertThat(saldo(c, destino, CAIXA, ANO_SEGUINTE)).isEqualByComparingTo("30.00");
                return null;
            });
        });
    }

    @Test
    void cadastroAnualComecaEmAnoValidoEAvancaSemSaltosPorEmpresa() {
        comCenario((schema, c) -> {
            UUID outra = executar(schema, () -> criarEmpresaSemCalendario());
            assertThatThrownBy(() -> executar(schema, () -> cadastrarAno(c.empresa(), 2028)))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_ordem_calendario"));
            assertThat(executar(schema, () -> anos(c.empresa()))).containsExactly(2026);
            executar(schema, () -> cadastrarAno(outra, 9998));
            executar(schema, () -> cadastrarAno(outra, 9999));
            assertThatThrownBy(() -> executar(schema, () -> cadastrarAno(outra, 10000)))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_ordem_calendario"))
                    .hasMessageContaining("9999");
            executar(schema, () -> cadastrarAno(c.empresa(), 2027));
            executar(schema, () -> cadastrarAno(c.empresa(), 2028));
            assertThatThrownBy(() -> executar(schema, () -> cadastrarAno(c.empresa(), 2025)))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_ordem_calendario"));
            assertThat(executar(schema, () -> anos(c.empresa()))).containsExactly(2026, 2027, 2028);
            assertThat(executar(schema, () -> anos(outra))).containsExactly(9998, 9999);
            assertThat(executar(schema, () -> jdbc.queryForObject("""
                    select count(*) from flyway_schema_history where success and version in
                       ('1','2','3','4','5','6','7','8','9','10','11','12')
                    """, Long.class))).isEqualTo(12L);
        });
    }

    @Test
    void protegeIdentidadeEExclusaoDeExercicioComContinuidade() {
        comCenario((schema, c) -> {
            UUID outra = executar(schema, this::criarEmpresaSemCalendario);
            assertThatThrownBy(() -> executar(schema, () -> excluirAno(c.calendario().exercicio())))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_exclusao_continuidade"));
            UUID intermediario = executar(schema, () -> cadastrarAno(c.empresa(), 2027));
            UUID ultimo = executar(schema, () -> cadastrarAno(c.empresa(), 2028));
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("""
                    update exercicio_contabil set ano=2029, data_inicio=date '2029-01-01', data_fim=date '2029-12-31'
                    where id=?
                    """, intermediario))).satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_identidade_calendario"));
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("update exercicio_contabil set empresa_id=? where id=?",
                    outra, intermediario))).satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_identidade_calendario"));
            assertThatThrownBy(() -> executar(schema, () -> excluirAno(intermediario)))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_exclusao_continuidade"));
            // Um último exercício ainda aberto e sem referências pode ser descartado.
            assertThat(executar(schema, () -> excluirAno(ultimo))).isEqualTo(1);
            executar(schema, () -> jdbc.update("update exercicio_contabil set status='ENCERRADO' where id=?", intermediario));
            assertThatThrownBy(() -> executar(schema, () -> excluirAno(intermediario)))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_exclusao_continuidade"));
            assertThat(executar(schema, () -> anos(c.empresa()))).containsExactly(2026, 2027);
            assertThat(executar(schema, () -> anos(outra))).isEmpty();
        });
    }

    @Test
    void upgradeV12DiagnosticaAnosAusentesSemPreencherLegadoOuEncerrarExercicio() {
        comSchema(schema -> {
            migrar(schema, "11");
            Cenario c = executar(schema, this::criarCenario);
            executar(schema, () -> {
                prepararAnoParaContinuidade(c);
                return cadastrarAno(c.empresa(), 2028);
            });
            migrar(schema, null);
            assertThatThrownBy(() -> executar(schema, () -> cadastrarAno(c.empresa(), 2029)))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_lacuna_legada"))
                    .hasMessageContaining("2027 a 2027 (entre 2026 e 2028)");
            assertThatThrownBy(() -> executar(schema, () -> fecharAno(c)))
                    .satisfies(erro -> conferirConstraint(erro, "23514", "ck_exercicio_lacuna_legada"))
                    .hasMessageContaining("2027 a 2027 (entre 2026 e 2028)");
            executar(schema, () -> {
                assertThat(anos(c.empresa())).containsExactly(2026, 2028);
                assertThat(statusPeriodo(c.calendario().dezembro())).isEqualTo("ABERTO");
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("""
                        select count(*) from flyway_schema_history where version='12' and success
                        """, Long.class)).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void fechamentoECadastroConcorrentesCriamOuReutilizamUmUnicoSucessor() {
        comCenario((schema, c) -> {
            Cenario outro = executar(schema, this::criarCenario);
            executar(schema, () -> {
                prepararAnoParaContinuidade(c);
                prepararAnoParaContinuidade(outro);
                return null;
            });
            var cadastroPrimeiro = disputar(schema, () -> cadastrarAno(c.empresa(), 2027), () -> fecharAno(c));
            assertThat(cadastroPrimeiro.erro()).isNull();
            var fechamentoPrimeiro = disputar(schema, () -> fecharAno(outro), () -> cadastrarAno(outro.empresa(), 2027));
            conferirConstraint(fechamentoPrimeiro.erro(), "23505", "uq_exercicio_empresa_ano");
            executar(schema, () -> {
                for (Cenario origem : List.of(c, outro)) {
                    assertThat(anos(origem.empresa())).containsExactly(2026, 2027);
                    UUID sucessor = jdbc.queryForObject("""
                            select id from exercicio_contabil where empresa_id=? and exercicio_origem_id=?
                            """, UUID.class, origem.empresa(), origem.calendario().exercicio());
                    assertThat(jdbc.queryForObject("""
                            select count(*) from lancamento_contabil where exercicio_id=? and origem='ABERTURA'
                            """, Long.class, sucessor)).isEqualTo(1L);
                    assertThat(jdbc.queryForObject("select count(*) from periodo_contabil where exercicio_id=?",
                            Long.class, sucessor)).isEqualTo(12L);
                    assertThat(razao.emitir(origem.empresa(), origem.usuario(), sucessor, origem.contas().get(CAIXA),
                            ANO_SEGUINTE, ANO_SEGUINTE).saldos().saldoFinal().valor()).isEqualByComparingTo("20");
                }
                return null;
            });
        });
    }

    @Test
    void cadastrosConcorrentesDecidemPelaSequenciaAtualizadaEmAmbasAsOrdens() {
        comCenario((schema, c) -> {
            Cenario outro = executar(schema, this::criarCenario);
            var consecutivoPrimeiro = disputar(schema, () -> cadastrarAno(c.empresa(), 2027),
                    () -> cadastrarAno(c.empresa(), 2028));
            assertThat(consecutivoPrimeiro.erro()).isNull();
            // Mantenha o bloqueio do pedido de 2028 enquanto 2027 entra na fila. O pedido
            // adiantado precisa ser rejeitado antes que o cadastro de 2027 possa prosseguir.
            var pronto = new CompletableFuture<Integer>();
            var tentando = new CompletableFuture<Integer>();
            var liberar = new CountDownLatch(1);
            try (var threads = Executors.newFixedThreadPool(2)) {
                try {
                    var adiantado = threads.submit(() -> executar(schema, () -> {
                        jdbc.queryForObject("select bloquear_e_validar_exercicios(?, true)", Integer.class, outro.empresa());
                        pronto.complete(pid());
                        aguardar(liberar);
                        return cadastrarAno(outro.empresa(), 2028);
                    }));
                    int bloqueadora = pronto.get(10, TimeUnit.SECONDS);
                    var consecutivo = threads.submit(() -> executar(schema, () -> {
                        tentando.complete(pid());
                        return cadastrarAno(outro.empresa(), 2027);
                    }));
                    aguardarBloqueio(bloqueadora, tentando.get(5, TimeUnit.SECONDS));
                    liberar.countDown();
                    assertThatThrownBy(() -> adiantado.get(10, TimeUnit.SECONDS))
                            .isInstanceOfSatisfying(ExecutionException.class, erro ->
                                    conferirConstraint(erro.getCause(), "23514", "ck_exercicio_ordem_calendario"));
                    assertThat(consecutivo.get(10, TimeUnit.SECONDS)).isNotNull();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } catch (ExecutionException | TimeoutException e) {
                    throw new AssertionError(e);
                } finally {
                    liberar.countDown();
                    threads.shutdownNow();
                }
            }
            assertThat(executar(schema, () -> anos(c.empresa()))).containsExactly(2026, 2027, 2028);
            assertThat(executar(schema, () -> anos(outro.empresa()))).containsExactly(2026, 2027);
        });
    }

    private void prepararAnoParaContinuidade(Cenario c) {
        confirmar(c, rascunho(c, c.calendario(), JANEIRO, CAIXA, RECEITA, "20"));
        fecharJaneiro(c);
        completarAnoDaFixture(c);
    }

    private UUID criarEmpresaSemCalendario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa", "Empresa", null);
        em.persist(empresa);
        em.flush();
        return empresa.getId();
    }

    private UUID cadastrarAno(UUID empresa, int ano) {
        return jdbc.queryForObject("""
                insert into exercicio_contabil (empresa_id, ano, data_inicio, data_fim)
                values (?, ?, make_date(?,1,1), make_date(?,12,31)) returning id
                """, UUID.class, empresa, ano, ano, ano);
    }

    private List<Integer> anos(UUID empresa) {
        return jdbc.queryForList("select ano from exercicio_contabil where empresa_id=? order by ano", Integer.class, empresa);
    }

    private int excluirAno(UUID exercicio) {
        return jdbc.update("delete from exercicio_contabil where id=?", exercicio);
    }

    private void conferirConstraint(Throwable erro, String estado, String nome) {
        assertThat(erro).isInstanceOfSatisfying(DataIntegrityViolationException.class, violacao ->
                assertThat(violacao.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, causa -> {
                    assertThat(causa.getSQLState()).isEqualTo(estado);
                    assertThat(causa.getServerErrorMessage()).isNotNull();
                    assertThat(causa.getServerErrorMessage().getConstraint()).isEqualTo(nome);
                }));
    }

    private Disputa disputar(String schema, Supplier<?> primeira, Supplier<?> segunda) {
        CompletableFuture<Integer> bloqueadora = new CompletableFuture<>();
        CompletableFuture<Integer> bloqueada = new CompletableFuture<>();
        CountDownLatch liberar = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = threads.submit(() -> executar(schema, () -> {
                Object resultado = primeira.get();
                bloqueadora.complete(pid());
                aguardar(liberar);
                return resultado;
            }));
            int pidA = bloqueadora.get(10, TimeUnit.SECONDS);
            Future<?> b = threads.submit(() -> executar(schema, () -> {
                bloqueada.complete(pid());
                return segunda.get();
            }));
            int pidB = bloqueada.get(5, TimeUnit.SECONDS);
            aguardarBloqueio(pidA, pidB);
            liberar.countDown();
            a.get(10, TimeUnit.SECONDS);
            try {
                b.get(10, TimeUnit.SECONDS);
                return new Disputa(null);
            } catch (ExecutionException e) {
                return new Disputa(e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        } finally {
            liberar.countDown();
            threads.shutdownNow();
            try {
                assertThat(threads.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private void aguardarBloqueio(int bloqueadora, int bloqueada) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limite) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
                    Boolean.class, bloqueadora, bloqueada))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("A segunda transação não aguardou o fechamento ou a confirmação.");
    }

    private void aguardar(CountDownLatch liberar) {
        try {
            assertThat(liberar.await(12, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private int pid() {
        return jdbc.queryForObject("select pg_backend_pid()", Integer.class);
    }

    private Dre emitirDre(Cenario c) {
        return dre.emitir(c.empresa(), c.usuario(), c.calendario().exercicio(), JANEIRO, DEZEMBRO);
    }

    private BigDecimal saldo(Cenario c, Calendario calendario, String codigo, LocalDate data) {
        return razao.emitir(c.empresa(), c.usuario(), calendario.exercicio(), c.contas().get(codigo),
                data, data).saldos().saldoFinal().valor();
    }

    private FechamentoContabilService.PeriodoEncerrado fecharJaneiro(Cenario c) {
        return fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.calendario().exercicio(), c.calendario().janeiro());
    }

    private FechamentoContabilService.ExercicioEncerrado fecharAno(Cenario c) {
        return fechamento.encerrarExercicio(c.empresa(), c.usuario(), c.calendario().exercicio());
    }

    private void completarAnoDaFixture(Cenario c) {
        CalendarioTestFixture.preencherEncerradosAteNovembro(jdbc, c.calendario().exercicio(), 2);
    }

    private String statusPeriodo(UUID periodo) {
        return jdbc.queryForObject("select status from periodo_contabil where id = ?", String.class, periodo);
    }

    private long confirmar(Cenario c, UUID id) {
        return lancamentos.confirmarLancamento(c.empresa(), c.usuario(), id).getNumero();
    }

    private UUID rascunho(Cenario c, Calendario calendario, LocalDate data, String debito, String credito, String valor) {
        UUID periodo = data.getMonthValue() == 12 ? calendario.dezembro() : calendario.janeiro();
        UUID id = lancamentos.criarRascunho(c.empresa(), c.usuario(), calendario.exercicio(), periodo, data, "Manual").getId();
        for (SaldoPorConta saldo : List.of(partida(c, debito, TipoPartida.DEBITO, valor),
                partida(c, credito, TipoPartida.CREDITO, valor))) {
            lancamentos.adicionarPartida(c.empresa(), c.usuario(), id, saldo.contaId(), saldo.tipo(), saldo.valor(), null);
        }
        return id;
    }

    private SaldoPorConta partida(Cenario c, String codigo, TipoPartida tipo, String valor) {
        return new SaldoPorConta(c.contas().get(codigo), tipo, new BigDecimal(valor));
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa", "Empresa", null);
        em.persist(empresa);
        Calendario calendario = criarCalendario(empresa, (short) 2026);
        UUID plano = planos.provisionar(empresa.getId(), usuario.getId()).getId();
        Map<String, UUID> planoPorCodigo = contas.findAllByPlanoContasIdOrderByCodigoAsc(plano).stream()
                .collect(toMap(ContaContabil::getCodigo, ContaContabil::getId));
        return new Cenario(empresa.getId(), usuario.getId(), calendario, planoPorCodigo);
    }

    private Calendario criarCalendario(Empresa empresa, short ano) {
        ExercicioContabil exercicio = new ExercicioContabil(empresa, ano, LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31));
        em.persist(exercicio);
        UUID janeiro = criarPeriodo(exercicio, YearMonth.of(ano, 1));
        UUID dezembro = criarPeriodo(exercicio, YearMonth.of(ano, 12));
        em.flush();
        return new Calendario(exercicio.getId(), janeiro, dezembro);
    }

    private UUID criarPeriodo(ExercicioContabil exercicio, YearMonth mes) {
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) mes.getMonthValue(), mes.atDay(1), mes.atEndOfMonth());
        em.persist(periodo);
        return periodo.getId();
    }

    private void comCenario(BiConsumer<String, Cenario> teste) {
        comSchema(schema -> {
            migrar(schema, null);
            teste.accept(schema, executar(schema, this::criarCenario));
        });
    }

    private void comSchema(Consumer<String> teste) {
        String schema = "lex_fechamento_" + UUID.randomUUID().toString().replace("-", "");
        try {
            teste.accept(schema);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private void migrar(String schema, String versao) {
        var configuracao = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (versao != null) {
            configuracao.target(versao);
        }
        configuracao.load().migrate();
    }

    private <T> T executar(String schema, Supplier<T> operacao) {
        TransactionTemplate transacao = new TransactionTemplate(transacoes);
        transacao.setTimeout(25);
        return transacao.execute(status -> {
            jdbc.execute("set local search_path to " + schema);
            jdbc.execute("set local statement_timeout = '15s'");
            return operacao.get();
        });
    }

    private record Disputa(Throwable erro) {
    }

    private record Calendario(UUID exercicio, UUID janeiro, UUID dezembro) {
    }

    private record Cenario(UUID empresa, UUID usuario, Calendario calendario, Map<String, UUID> contas) {
    }
}
