package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.AquisicaoAtivo;
import br.com.lex.application.contabil.dto.FichaAtivo;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AtivoPatrimonialServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 5, 15);
    private static final String CAIXA = "1.1.01.001";
    private static final String FORNECEDORES = "2.1.01.001";
    private static final String TERRENOS = "1.2.03.001";
    private static final String MAQUINAS = "1.2.03.003";
    private static final String REDUTORA = "1.2.03.102";
    private static final String DESPESA = "6.2.006";

    @Autowired private AtivoPatrimonialService ativos;
    @Autowired private PlanoContasService planos;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private ContaContabilRepository contas;
    @Autowired private FechamentoContabilService fechamento;
    @Autowired private LivroDiarioService diario;
    @Autowired private LivroRazaoService razao;
    @Autowired private BalanceteService balancete;
    @Autowired private BalancoPatrimonialService balanco;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transacoes;

    @Test
    void adquireAVistaEPrazoEReconciliaOsDemonstrativos() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            FichaAtivo maquina = executar(schema, () -> ativos.registrarAquisicao(maquina(c, " maq-1 ", CAIXA)));
            FichaAtivo terreno = executar(schema, () -> ativos.registrarAquisicao(aquisicao(c, "TER-1",
                    c.conta(TERRENOS), c.conta(FORNECEDORES), DATA, null, "2500", false, "2500", null, null)));
            assertThat(maquina.codigo()).isEqualTo("MAQ-1");
            assertThat(maquina.numeroContabil()).isEqualTo(1);
            assertThat(maquina.contaRedutoraId()).isEqualTo(c.conta(REDUTORA));
            assertThat(maquina.contaDespesaId()).isEqualTo(c.conta(DESPESA));
            assertThat(maquina.baseDepreciavel()).isEqualByComparingTo("1000.00");
            assertThat(maquina.situacao()).isEqualTo(FichaAtivo.Situacao.DISPONIBILIDADE_REGISTRADA);
            assertThat(terreno.numeroContabil()).isEqualTo(2);
            assertThat(terreno.depreciavel()).isFalse();
            assertThat(terreno.vidaUtilMeses()).isNull();
            assertThat(terreno.contaRedutoraId()).isNull();
            assertThat(terreno.baseDepreciavel()).isEqualByComparingTo("0");
            assertThat(terreno.situacao()).isEqualTo(FichaAtivo.Situacao.AGUARDANDO_DISPONIBILIDADE);
            executar(schema, () -> {
                assertThat(ativos.listar(c.empresa(), c.usuario())).containsExactly(maquina, terreno);
                assertThat(ativos.consultar(c.empresa(), c.usuario(), maquina.id())).isEqualTo(maquina);
                conferirEscrituracao(c, maquina, CAIXA);
                conferirEscrituracao(c, terreno, FORNECEDORES);
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.criarRascunhoEstorno(c.empresa(), c.usuario(),
                    maquina.lancamentoId(), c.exercicio(), c.periodo(), DATA, "Estorno")))
                    .hasMessageContaining("módulo de origem");
        });
    }

    private void conferirEscrituracao(Cenario c, FichaAtivo ativo, String contrapartida) {
        var lancamento = diario.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA).lancamentos().stream()
                .filter(l -> l.referencia().id().equals(ativo.lancamentoId())).findFirst().orElseThrow();
        assertThat(lancamento.referencia().numero()).isEqualTo(ativo.numeroContabil());
        assertThat(lancamento.origem()).isEqualTo(OrigemLancamento.ATIVO);
        assertThat(lancamento.status()).isEqualTo(StatusLancamento.CONFIRMADO);
        assertThat(lancamento.partidas()).map(p -> new SaldoPorConta(p.contaId(), p.tipo(), p.valor()))
                .containsExactlyInAnyOrder(new SaldoPorConta(ativo.contaPatrimonialId(), TipoPartida.DEBITO, ativo.custoAquisicao()),
                        new SaldoPorConta(c.conta(contrapartida), TipoPartida.CREDITO, ativo.custoAquisicao()));
        assertThat(razao.emitir(c.empresa(), c.usuario(), c.exercicio(), ativo.contaPatrimonialId(), DATA, DATA)
                .saldos().saldoFinal().valor()).isEqualByComparingTo(ativo.custoAquisicao());
        assertThat(balancete.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA, DATA).linhas())
                .filteredOn(l -> l.conta().id().equals(ativo.contaPatrimonialId())).singleElement()
                .satisfies(l -> assertThat(l.saldos().saldoFinal().valor()).isEqualByComparingTo(ativo.custoAquisicao()));
        var bp = balanco.emitir(c.empresa(), c.usuario(), c.exercicio(), DATA);
        assertThat(bp.equilibrado()).isTrue();
        assertThat(bp.secoes().stream().flatMap(s -> s.linhas().stream()))
                .filteredOn(l -> l.conta().id().equals(ativo.contaPatrimonialId())).singleElement()
                .satisfies(l -> assertThat(l.valorApresentado()).isEqualByComparingTo(ativo.custoAquisicao()));
        assertThat(jdbc.queryForObject("""
                select count(*) from auditoria_evento where entidade = 'ativo_patrimonial' and acao = 'AQUISICAO'
                  and empresa_id = ? and usuario_id = ? and entidade_id = ?
                  and dados_novos->>'lancamentoId' = ? and dados_novos->>'numeroContabil' = ?
                  and (dados_novos->>'custoAquisicao')::numeric = ?
                """, Long.class, c.empresa(), c.usuario(), ativo.id(), ativo.lancamentoId().toString(),
                Long.toString(ativo.numeroContabil()), ativo.custoAquisicao())).isEqualTo(1L);
    }

    @Test
    void validaValoresVidaUtilDisponibilidadeEClassificacao() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            UUID bem = c.conta(MAQUINAS);
            UUID caixa = c.conta(CAIXA);
            UUID despesa = c.conta(DESPESA);
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "0", true, "0", 12, despesa), "positivo");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "1.001", true, "0", 12, despesa), "duas casas");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "-1", 12, despesa), "residual");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "100.01", 12, despesa), "residual");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "0", 0, despesa), "vida útil");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA.minusDays(1), "100", true, "0", 12, despesa),
                    "anteceder");
            rejeitar(schema, aquisicao(c, "A", c.conta(REDUTORA), caixa, DATA, DATA, "100", true, "0", 12, despesa),
                    "Imobilizado");
            rejeitar(schema, aquisicao(c, "A", c.conta(TERRENOS), caixa, DATA, DATA, "100", true, "0", 12, despesa),
                    "redutora");
            rejeitar(schema, aquisicao(c, "A", bem, caixa, DATA, DATA, "100", true, "0", 12, caixa), "despesa");
            rejeitar(schema, aquisicao(c, "A", bem, despesa, DATA, DATA, "100", true, "0", 12, despesa), "contrapartida");
            rejeitar(schema, aquisicao(c, "A", c.conta(TERRENOS), caixa, DATA, DATA, "100", false, "0", 12, null), "Vida útil");
            executar(schema, () -> jdbc.update("update conta_contabil set ativa = false where id = ?", c.conta(REDUTORA)));
            rejeitar(schema, maquina(c, "A", CAIXA), "analíticas e ativas");
            executar(schema, () -> jdbc.update("update conta_contabil set ativa = true where id = ?", c.conta(REDUTORA)));
            FichaAtivo pendente = executar(schema, () -> ativos.registrarAquisicao(
                    aquisicao(c, "A", bem, caixa, DATA, null, "100", true, "100", 12, despesa)));
            assertThat(pendente.baseDepreciavel()).isEqualByComparingTo("0");
            assertThat(pendente.dataDisponibilidade()).isNull();
            assertThat(pendente.situacao()).isEqualTo(FichaAtivo.Situacao.AGUARDANDO_DISPONIBILIDADE);
            assertThat(pendente.numeroContabil()).isEqualTo(1);
        });
    }

    @Test
    void isolaEmpresasEReverteCadastroEscrituracaoSequenciaEAuditoria() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            Cenario outra = executar(schema, this::criarCenario);
            FichaAtivo primeiro = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA)));
            executar(schema, () -> ativos.registrarAquisicao(maquina(outra, "A", CAIXA)));
            assertThatThrownBy(() -> executar(schema, () -> ativos.registrarAquisicao(maquina(c, " a ", CAIXA))))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23505", "uq_ativo_patrimonial_codigo"));
            assertThatThrownBy(() -> executar(schema, () -> ativos.consultar(outra.empresa(), outra.usuario(), primeiro.id())))
                    .hasMessageContaining("não encontrado");
            assertThatThrownBy(() -> executar(schema, () -> ativos.listar(c.empresa(), outra.usuario())))
                    .hasMessageContaining("não pertence");
            rejeitar(schema, aquisicao(c, "B", outra.conta(MAQUINAS), c.conta(CAIXA), DATA, DATA,
                    "100", true, "0", 12, c.conta(DESPESA)), "Conta patrimonial");
            rejeitar(schema, aquisicao(c, "B", c.conta(MAQUINAS), outra.conta(CAIXA), DATA, DATA,
                    "100", true, "0", 12, c.conta(DESPESA)), "contrapartida");
            assertThatThrownBy(() -> executar(schema, () -> {
                ativos.registrarAquisicao(maquina(c, "B", CAIXA));
                throw new IllegalStateException("Falha após confirmar e auditar.");
            })).hasMessageContaining("Falha após confirmar");
            executar(schema, () -> {
                assertThat(ativos.listar(c.empresa(), c.usuario())).containsExactly(primeiro);
                assertThat(jdbc.queryForObject("select count(*) from ativo_patrimonial", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from lancamento_contabil", Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select count(*) from partida_contabil", Long.class)).isEqualTo(4L);
                assertThat(jdbc.queryForObject("select count(*) from auditoria_evento where entidade = 'ativo_patrimonial'",
                        Long.class)).isEqualTo(2L);
                assertThat(jdbc.queryForObject("select ultimo_numero from sequencia_lancamento where empresa_id = ?",
                        Long.class, c.empresa())).isEqualTo(1L);
                return null;
            });
        });
    }

    @Test
    void respeitaAberturaECalendarioETransportaSaldoSemDuplicarAtivo() {
        comSchema(schema -> {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            executar(schema, () -> lancamentos.registrarSaldosIniciais(new AberturaContabil(
                    c.empresa(), c.usuario(), c.exercicio(), c.periodo(), DATA, "Migração",
                    List.of(new SaldoPorConta(c.conta(CAIXA), TipoPartida.DEBITO, BigDecimal.ONE),
                            new SaldoPorConta(c.conta("3.1.001"), TipoPartida.CREDITO, BigDecimal.ONE)))));
            rejeitar(schema, aquisicao(c, "A", c.conta(TERRENOS), c.conta(CAIXA), DATA.minusDays(1), null,
                    "100", false, "0", null, null), "anterior à abertura");
            FichaAtivo adquirido = executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", FORNECEDORES)));
            assertThat(adquirido.numeroContabil()).isEqualTo(2);
            executar(schema, () -> fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo()));
            rejeitar(schema, maquina(c, "B", CAIXA), "período deve estar aberto");
            LocalDate dezembro = LocalDate.of(2026, 12, 31);
            LocalDate janeiro = dezembro.plusDays(1);
            Cenario seguinte = executar(schema, () -> {
                em.persist(new PeriodoContabil(em.getReference(ExercicioContabil.class, c.exercicio()),
                        (short) 12, dezembro.withDayOfMonth(1), dezembro));
                ExercicioContabil proximo = new ExercicioContabil(em.getReference(Empresa.class, c.empresa()),
                        (short) 2027, janeiro, janeiro.plusYears(1).minusDays(1));
                em.persist(proximo);
                PeriodoContabil periodo = new PeriodoContabil(proximo, (short) 1, janeiro, janeiro.plusDays(30));
                em.persist(periodo);
                return new Cenario(c.empresa(), c.usuario(), proximo.getId(), periodo.getId(), c.plano(), c.contas());
            });
            rejeitar(schema, aquisicao(seguinte, "B", c.conta(TERRENOS), c.conta(CAIXA), janeiro, null,
                    "100", false, "0", null, null), "continuidade do exercício anterior");
            var encerramento = executar(schema, () -> fechamento.encerrarExercicio(c.empresa(), c.usuario(), c.exercicio()));
            assertThat(encerramento.lancamentoAberturaId()).isNotNull();
            executar(schema, () -> {
                assertThat(ativos.listar(c.empresa(), c.usuario())).containsExactly(adquirido);
                var movimentos = razao.emitir(c.empresa(), c.usuario(), encerramento.exercicioDestinoId(),
                        c.conta(MAQUINAS), janeiro, janeiro);
                assertThat(movimentos.movimentos()).singleElement()
                        .satisfies(m -> assertThat(m.origem()).isEqualTo(OrigemLancamento.ABERTURA));
                assertThat(movimentos.saldos().saldoFinal().valor()).isEqualByComparingTo(adquirido.custoAquisicao());
                return null;
            });
        });
    }

    @Test
    void impedeDuasAquisicoesConcorrentesDoMesmoCodigo() throws Exception {
        String schema = novoSchema();
        try {
            migrar(schema, null);
            Cenario c = executar(schema, this::criarCenario);
            var executor = Executors.newFixedThreadPool(2);
            var pronta = new CompletableFuture<Integer>();
            var tentando = new CompletableFuture<Integer>();
            var liberar = new CountDownLatch(1);
            try {
                var primeira = executor.submit(() -> executar(schema, () -> {
                    var adquirido = ativos.registrarAquisicao(maquina(c, "A", CAIXA));
                    pronta.complete(pid());
                    aguardar(liberar);
                    return adquirido;
                }));
                int bloqueadora = pronta.get(10, TimeUnit.SECONDS);
                var segunda = executor.submit(() -> executar(schema, () -> {
                    tentando.complete(pid());
                    return ativos.registrarAquisicao(maquina(c, "a", FORNECEDORES));
                }));
                int bloqueada = tentando.get(5, TimeUnit.SECONDS);
                long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean aguardou = false;
                while (!aguardou && System.nanoTime() < limite) {
                    aguardou = Boolean.TRUE.equals(jdbc.queryForObject("""
                            select ? = any(pg_blocking_pids(pid)) and query ilike '%ativo_patrimonial%'
                            from pg_stat_activity where pid = ?
                            """, Boolean.class, bloqueadora, bloqueada));
                    if (!aguardou) {
                        TimeUnit.MILLISECONDS.sleep(25);
                    }
                }
                assertThat(aguardou).isTrue();
                liberar.countDown();
                FichaAtivo adquirido = primeira.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> segunda.get(10, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ExecutionException.class, erro ->
                                conferirViolacaoPostgres(erro.getCause(), "23505", "uq_ativo_patrimonial_codigo"));
                assertThat(executar(schema, () -> ativos.listar(c.empresa(), c.usuario()))).containsExactly(adquirido);
                assertThat(executar(schema, () -> jdbc.queryForObject(
                        "select ultimo_numero from sequencia_lancamento", Long.class))).isEqualTo(1L);
            } finally {
                liberar.countDown();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            removerSchema(schema);
        }
    }

    @Test
    void instalaDesdeV1EAtualizaV8ComVinculoContabilObrigatorio() {
        comSchema(schema -> {
            migrar(schema, "8");
            Cenario c = executar(schema, this::criarCenario);
            migrar(schema, null);
            assertThatThrownBy(() -> executar(schema, () -> jdbc.update("""
                    insert into ativo_patrimonial (id, empresa_id, plano_contas_id, usuario_id, codigo, descricao,
                        conta_patrimonial_id, data_aquisicao, custo_aquisicao, depreciavel, valor_residual)
                    values (?, ?, ?, ?, 'SEM-LANCAMENTO', 'Terreno', ?, ?, 1, false, 0)
                    """, UUID.randomUUID(), c.empresa(), c.plano(), c.usuario(), c.conta(TERRENOS), DATA)))
                    .satisfies(erro -> conferirViolacaoPostgres(erro, "23514", "ck_ativo_patrimonial_contabilizado"));
            assertThat(executar(schema, () -> ativos.listar(c.empresa(), c.usuario()))).isEmpty();
            assertThat(executar(schema, () -> ativos.registrarAquisicao(maquina(c, "A", CAIXA))).numeroContabil())
                    .isEqualTo(1);
            assertThat(executar(schema, () -> jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where success and version is not null", Long.class)))
                    .isEqualTo(9L);
        });
    }

    private void conferirViolacaoPostgres(Throwable erro, String sqlState, String constraint) {
        assertThat(erro).isInstanceOfSatisfying(DataIntegrityViolationException.class, violacao ->
                assertThat(violacao.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, causa -> {
                    assertThat(causa.getSQLState()).isEqualTo(sqlState);
                    assertThat(causa.getServerErrorMessage()).isNotNull();
                    assertThat(causa.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                }));
    }

    private AquisicaoAtivo maquina(Cenario c, String codigo, String contrapartida) {
        return aquisicao(c, codigo, c.conta(MAQUINAS), c.conta(contrapartida), DATA, DATA.plusDays(1),
                "1200", true, "200", 24, c.conta(DESPESA));
    }

    private AquisicaoAtivo aquisicao(Cenario c, String codigo, UUID bem, UUID contrapartida, LocalDate data,
                                    LocalDate disponibilidade, String custo, boolean depreciavel, String residual,
                                    Integer meses, UUID despesa) {
        return new AquisicaoAtivo(c.empresa(), c.usuario(), c.exercicio(), c.periodo(), codigo, "Bem patrimonial",
                bem, contrapartida, data, disponibilidade, new BigDecimal(custo), depreciavel, new BigDecimal(residual),
                meses, despesa, "Aquisição patrimonial");
    }

    private void rejeitar(String schema, AquisicaoAtivo aquisicao, String mensagem) {
        assertThatThrownBy(() -> executar(schema, () -> ativos.registrarAquisicao(aquisicao)))
                .hasMessageContaining(mensagem);
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa", "Empresa", null);
        em.persist(empresa);
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) 2026,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        em.persist(exercicio);
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) 5, DATA.withDayOfMonth(1), DATA.withDayOfMonth(31));
        em.persist(periodo);
        em.flush();
        UUID plano = planos.provisionar(empresa.getId(), usuario.getId()).getId();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), periodo.getId(), plano,
                contas.findAllByPlanoContasIdOrderByCodigoAsc(plano).stream()
                        .collect(toMap(c -> c.getCodigo(), c -> c.getId())));
    }

    private String novoSchema() {
        return "lex_ativo_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void comSchema(Consumer<String> teste) {
        String schema = novoSchema();
        try {
            teste.accept(schema);
        } finally {
            removerSchema(schema);
        }
    }

    private void removerSchema(String schema) {
        jdbc.execute("drop schema if exists " + schema + " cascade");
    }

    private void migrar(String schema, String alvo) {
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (alvo != null) {
            config.target(alvo);
        }
        config.load().migrate();
    }

    private <T> T executar(String schema, Supplier<T> operacao) {
        var transacao = new TransactionTemplate(transacoes);
        transacao.setTimeout(25);
        return transacao.execute(status -> {
            jdbc.execute("set local search_path to " + schema);
            jdbc.execute("set local statement_timeout = '15s'");
            return operacao.get();
        });
    }

    private int pid() {
        return jdbc.queryForObject("select pg_backend_pid()", Integer.class);
    }

    private void aguardar(CountDownLatch liberar) {
        try {
            assertThat(liberar.await(12, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo, UUID plano, Map<String, UUID> contas) {
        UUID conta(String codigo) {
            return contas.get(codigo);
        }
    }
}
