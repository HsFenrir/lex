package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaConfirmada;
import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.LivroDiario;
import br.com.lex.application.contabil.dto.LivroRazao;
import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
import java.time.YearMonth;
import java.util.List;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AberturaContabilServiceTest {

    @Autowired private FechamentoContabilService fechamento;

    private static final LocalDate DATA = LocalDate.of(2026, 5, 15);
    private static final BigDecimal VALOR = new BigDecimal("120.45");

    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private LivroDiarioService diario;
    @Autowired private LivroRazaoService razao;
    @Autowired private ModeloContaContabilRepository modelos;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transacoes;

    @Test
    void confirmaAberturaNoMeioDoExercicioEIntegraDiarioERazao() {
        comCenario((schema, dados) -> {
            AberturaConfirmada abertura = executar(schema, () -> abrir(dados));
            executar(schema, () -> {
                assertThat(abertura.origem()).isEqualTo(OrigemLancamento.ABERTURA);
                assertThat(abertura.numero()).isEqualTo(1);
                assertThat(abertura.confirmadoPor()).isEqualTo(dados.usuario());
                assertThat(abertura.confirmadoEm()).isNotNull();

                LivroDiario livro = diario.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), DATA, DATA);
                assertThat(livro.lancamentos()).singleElement().satisfies(l -> {
                    assertThat(l.referencia().id()).isEqualTo(abertura.lancamentoId());
                    assertThat(l.status()).isEqualTo(StatusLancamento.CONFIRMADO);
                    assertThat(l.origem()).isEqualTo(OrigemLancamento.ABERTURA);
                    assertThat(l.totalDebitos()).isEqualByComparingTo(VALOR);
                    assertThat(l.totalCreditos()).isEqualByComparingTo(VALOR);
                    assertThat(l.partidas()).hasSize(2);
                });
                LivroRazao contaCredora = razao.emitir(dados.empresa(), dados.usuario(), dados.exercicio(),
                        dados.credito(), DATA.plusDays(1), DATA.plusDays(1));
                assertThat(contaCredora.conta().natureza()).isEqualTo(NaturezaConta.DEVEDORA);
                assertThat(contaCredora.movimentos()).isEmpty();
                assertThat(contaCredora.saldos().saldoAnterior().valor()).isEqualByComparingTo(VALOR.negate());
                assertThat(contaCredora.saldos().saldoFinal().valor()).isEqualByComparingTo(VALOR.negate());
                LivroRazao contaDevedora = razao.emitir(dados.empresa(), dados.usuario(), dados.exercicio(),
                        dados.debito(), DATA, DATA);
                assertThat(contaDevedora.movimentos()).singleElement().satisfies(m -> {
                    assertThat(m.tipo()).isEqualTo(TipoPartida.DEBITO);
                    assertThat(m.valor()).isEqualByComparingTo(VALOR);
                });
                assertThat(contaDevedora.saldos().saldoFinal().valor()).isEqualByComparingTo(VALOR);
                conferirPersistencia(dados, 1, 2, 1L);
                return null;
            });
        });
    }

    @Test
    void rejeitaColecaoInsuficienteRepeticaoDesequilibrioEValoresInvalidos() {
        comCenario((schema, dados) -> {
            rejeitar(schema, dados, List.of(debito(dados, VALOR)), IllegalArgumentException.class, "duas contas");
            rejeitar(schema, dados, List.of(debito(dados, VALOR), debito(dados, VALOR)),
                    IllegalArgumentException.class, "única vez");
            rejeitar(schema, dados, List.of(debito(dados, VALOR), credito(dados.credito(), BigDecimal.ONE)),
                    IllegalStateException.class, "devem ser iguais");
            for (String valor : List.of("0", "-1", "0.001", "100000000000000000.00")) {
                assertThatThrownBy(() -> executar(schema, () -> lancamentos.registrarSaldosIniciais(
                        pedido(dados, List.of(debito(dados, VALOR), credito(dados.credito(), new BigDecimal(valor)))))))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            conferirVazio(schema, dados);
        });
    }

    @Test
    void rejeitaContasInaptasEUsuarioNaoAutorizado() {
        comCenario((schema, dados) -> {
            Cenario outra = executar(schema, this::criarCenario);
            rejeitar(schema, dados, List.of(debito(dados, VALOR), credito(outra.credito(), VALOR)),
                    IllegalArgumentException.class, "plano");
            rejeitar(schema, dados, List.of(debito(dados, VALOR), credito(dados.sintetica(), VALOR)),
                    IllegalArgumentException.class, "analítica e ativa");
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.registrarSaldosIniciais(
                    new AberturaContabil(dados.empresa(), outra.usuario(), dados.exercicio(), dados.periodo(),
                            DATA, "Abertura", saldos(dados)))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não pertence");
            executar(schema, () -> jdbc.update("update conta_contabil set ativa = false where id = ?", dados.credito()));
            rejeitar(schema, dados, saldos(dados), IllegalArgumentException.class, "analítica e ativa");
            conferirVazio(schema, dados);
        });
    }

    @Test
    void rejeitaCalendarioIncompativelOuEncerrado() {
        comCenario((schema, dados) -> {
            Cenario outra = executar(schema, this::criarCenario);
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.registrarSaldosIniciais(
                    new AberturaContabil(dados.empresa(), dados.usuario(), outra.exercicio(), dados.periodo(),
                            DATA, "Abertura", saldos(dados)))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Exercício");
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.registrarSaldosIniciais(
                    new AberturaContabil(dados.empresa(), dados.usuario(), dados.exercicio(), outra.periodo(),
                            DATA, "Abertura", saldos(dados)))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Período");
            assertThatThrownBy(() -> executar(schema, () -> lancamentos.registrarSaldosIniciais(
                    new AberturaContabil(dados.empresa(), dados.usuario(), dados.exercicio(), dados.periodo(),
                            DATA.minusMonths(1), "Abertura", saldos(dados)))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dentro do período");
            executar(schema, () -> jdbc.update("update periodo_contabil set status = 'ENCERRADO' where id = ?", dados.periodo()));
            rejeitar(schema, dados, saldos(dados), IllegalStateException.class, "período deve estar aberto");
            executar(schema, () -> jdbc.update("update exercicio_contabil set status = 'ENCERRADO' where id = ?", dados.exercicio()));
            rejeitar(schema, dados, saldos(dados), IllegalStateException.class, "exercício deve estar aberto");
            conferirVazio(schema, dados);
        });
    }

    @Test
    void bloqueiaDuplicidadeMesmoDepoisDoEstorno() {
        comCenario((schema, dados) -> {
            AberturaConfirmada abertura = executar(schema, () -> abrir(dados));
            rejeitar(schema, dados, saldos(dados), IllegalStateException.class, "já possui abertura");
            executar(schema, () -> {
                UUID estorno = lancamentos.criarRascunhoEstorno(dados.empresa(), dados.usuario(), abertura.lancamentoId(),
                        dados.exercicio(), dados.periodo(), DATA.plusDays(1), "Estorno da abertura").getId();
                return lancamentos.confirmarEstorno(dados.empresa(), dados.usuario(), estorno).getId();
            });
            rejeitar(schema, dados, saldos(dados), IllegalStateException.class, "ainda que estornada");
            executar(schema, () -> {
                conferirPersistencia(dados, 2, 4, 2L);
                LivroDiario livro = diario.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), DATA, DATA.plusDays(1));
                assertThat(livro.lancamentos()).extracting(LivroDiario.Lancamento::status)
                        .containsExactly(StatusLancamento.ESTORNADO, StatusLancamento.CONFIRMADO);
                return null;
            });
        });
    }

    @Test
    void permiteRascunhoMasRejeitaEscrituracaoPreexistenteEExercicioSubsequente() {
        comCenario((schema, dados) -> {
            Cenario outra = executar(schema, this::criarCenario);
            executar(schema, () -> criarManual(outra));
            assertThat(executar(schema, () -> abrir(outra)).numero()).isEqualTo(1);

            executar(schema, () -> {
                UUID manual = criarManual(dados);
                lancamentos.confirmarLancamento(dados.empresa(), dados.usuario(), manual);
                return null;
            });
            rejeitar(schema, dados, saldos(dados), IllegalStateException.class, "movimentos escriturados");
            Cenario seguinte = executar(schema, () -> criarExercicioSeguinte(outra));
            rejeitar(schema, seguinte, saldos(seguinte), IllegalStateException.class, "primeiro exercício");
            conferirVazio(schema, seguinte);
        });
    }

    @Test
    void reverteLancamentoPartidasENumeracaoQuandoBancoRejeitaConfirmacao() {
        comCenario((schema, dados) -> {
            executar(schema, () -> {
                jdbc.execute("""
                        alter table lancamento_contabil add constraint teste_bloquear_abertura
                        check (origem <> 'ABERTURA' or status <> 'CONFIRMADO')
                        """);
                return null;
            });
            assertThatThrownBy(() -> executar(schema, () -> abrir(dados)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(erro -> assertThat(((DataIntegrityViolationException) erro).getMostSpecificCause().getMessage())
                            .contains("teste_bloquear_abertura"));
            conferirVazio(schema, dados);
        });
    }

    @Test
    void somenteUmaAberturaConcorrentePersisteEmTransacoesIndependentes() {
        comCenario((schema, dados) -> {
            CountDownLatch prontas = new CountDownLatch(2);
            CountDownLatch largada = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<AberturaConfirmada> primeira = executor.submit(() -> tentarConcorrente(schema, dados, prontas, largada));
                Future<AberturaConfirmada> segunda = executor.submit(() -> tentarConcorrente(schema, dados, prontas, largada));
                assertThat(prontas.await(10, TimeUnit.SECONDS)).isTrue();
                largada.countDown();
                AberturaConfirmada a = primeira.get(20, TimeUnit.SECONDS);
                AberturaConfirmada b = segunda.get(20, TimeUnit.SECONDS);
                assertThat((a == null) != (b == null)).isTrue();
                AberturaConfirmada vencedora = a == null ? b : a;
                executar(schema, () -> {
                    conferirPersistencia(dados, 1, 2, 1L);
                    assertThat(jdbc.queryForObject("select id from lancamento_contabil where status = 'CONFIRMADO'",
                            UUID.class)).isEqualTo(vencedora.lancamentoId());
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                throw new AssertionError(e);
            } finally {
                largada.countDown();
                executor.shutdownNow();
                aguardarEncerramento(executor);
            }
        });
    }

    @Test
    void revalidaRascunhoAnteriorEPermiteMesmaDataPosteriorERetroativoValido() {
        comCenario((schema, dados) -> {
            UUID anterior = executar(schema, () -> criarManual(dados, DATA.minusDays(1)));
            assertThat(executar(schema, () -> abrir(dados)).numero()).isEqualTo(1);

            assertThatThrownBy(() -> executar(schema, () -> confirmarManual(dados, anterior)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("anterior à abertura");
            executar(schema, () -> {
                conferirPersistencia(dados, 2, 4, 1L);
                conferirRascunho(anterior);
                assertThat(confirmarManual(dados, criarManual(dados, DATA))).isEqualTo(2L);
                assertThat(confirmarManual(dados, criarManual(dados, DATA.plusDays(2)))).isEqualTo(3L);
                assertThat(confirmarManual(dados, criarManual(dados, DATA.plusDays(1)))).isEqualTo(4L);
                return null;
            });
        });
    }

    @Test
    void preservaMarcoAposEstornoESomenteNoExercicioDaEmpresa() {
        comCenario((schema, dados) -> {
            AberturaConfirmada abertura = executar(schema, () -> abrir(dados));
            UUID anterior = executar(schema, () -> {
                UUID estorno = lancamentos.criarRascunhoEstorno(dados.empresa(), dados.usuario(), abertura.lancamentoId(),
                        dados.exercicio(), dados.periodo(), DATA.plusDays(1), "Estorno").getId();
                lancamentos.confirmarEstorno(dados.empresa(), dados.usuario(), estorno);
                return criarManual(dados, DATA.minusDays(1));
            });
            assertThatThrownBy(() -> executar(schema, () -> confirmarManual(dados, anterior)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("anterior à abertura");
            executar(schema, () -> {
                conferirRascunho(anterior);
                conferirPersistencia(dados, 3, 6, 2L);
                // Descarta somente o rascunho da fixture após verificar que a rejeição o preservou.
                List<UUID> partidasDoRascunho = em.createQuery("""
                        select p.id from PartidaContabil p where p.lancamentoId = :lancamento
                        """, UUID.class).setParameter("lancamento", anterior).getResultList();
                for (UUID partidaId : partidasDoRascunho) {
                    lancamentos.removerPartida(dados.empresa(), dados.usuario(), anterior, partidaId);
                }
                em.flush();
                em.remove(em.find(LancamentoContabil.class, anterior));
                em.flush();

                assertThat(confirmarManual(dados, criarManual(dados, DATA.plusDays(2)))).isEqualTo(3L);

                Cenario outra = criarCenario();
                assertThat(confirmarManual(outra, criarManual(outra, DATA.minusDays(1)))).isEqualTo(1L);
                Cenario seguinte = criarExercicioSeguinte(dados);
                LocalDate fim = dados.data().withMonth(12).withDayOfMonth(31);
                em.persist(new PeriodoContabil(em.getReference(ExercicioContabil.class, dados.exercicio()),
                        (short) 12, fim.withDayOfMonth(1), fim));
                em.flush();
                fechamento.encerrarPeriodo(dados.empresa(), dados.usuario(), dados.exercicio(), dados.periodo());
                fechamento.encerrarExercicio(dados.empresa(), dados.usuario(), dados.exercicio());
                assertThat(confirmarManual(seguinte, criarManual(seguinte, seguinte.data().minusDays(1))))
                        .isEqualTo(2L);
                return null;
            });
        });
    }

    @Test
    void aberturaConcorrenteComPrecedenciaImpedeConfirmacaoAnterior() {
        comCenario((schema, dados) -> {
            UUID manual = executar(schema, () -> criarManual(dados, DATA.minusDays(1)));
            ResultadoDisputa disputa = disputarSequencia(schema, () -> abrir(dados).numero(),
                    () -> confirmarManual(dados, manual));

            assertThat(disputa.primeiro()).isEqualTo(1L);
            assertThat(disputa.erroSegundo()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("anterior à abertura");
            executar(schema, () -> {
                conferirPersistencia(dados, 2, 4, 1L);
                conferirAberturaNumeroUm(dados);
                conferirRascunho(manual);
                return null;
            });
        });
    }

    @Test
    void aberturaConcorrenteComPrecedenciaPermiteConfirmacaoComDataValida() {
        comCenario((schema, dados) -> {
            UUID manual = executar(schema, () -> criarManual(dados, DATA));
            ResultadoDisputa disputa = disputarSequencia(schema, () -> abrir(dados).numero(),
                    () -> confirmarManual(dados, manual));

            assertThat(disputa.primeiro()).isEqualTo(1L);
            assertThat(disputa.erroSegundo()).isNull();
            assertThat(disputa.segundo()).isEqualTo(2L);
            executar(schema, () -> {
                conferirPersistencia(dados, 2, 4, 2L);
                conferirAberturaNumeroUm(dados);
                assertThat(jdbc.queryForObject(
                        "select numero from lancamento_contabil where id = ? and status = 'CONFIRMADO'", Long.class, manual))
                        .isEqualTo(2L);
                return null;
            });
        });
    }

    @Test
    void confirmacaoManualComPrecedenciaImpedeAberturaConcorrente() {
        comCenario((schema, dados) -> {
            UUID manual = executar(schema, () -> criarManual(dados, DATA.minusDays(1)));
            ResultadoDisputa disputa = disputarSequencia(schema, () -> confirmarManual(dados, manual),
                    () -> abrir(dados).numero());

            assertThat(disputa.primeiro()).isEqualTo(1L);
            assertThat(disputa.erroSegundo()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("primeiro lançamento");
            executar(schema, () -> {
                conferirPersistencia(dados, 1, 2, 1L);
                assertThat(jdbc.queryForObject("""
                        select id from lancamento_contabil where numero = 1
                          and status = 'CONFIRMADO' and origem = 'MANUAL'
                        """, UUID.class)).isEqualTo(manual);
                return null;
            });
        });
    }

    private ResultadoDisputa disputarSequencia(String schema, Supplier<Long> primeira, Supplier<Long> segunda) {
        CompletableFuture<Integer> primeiraSessao = new CompletableFuture<>();
        CompletableFuture<Integer> segundaSessao = new CompletableFuture<>();
        CountDownLatch liberarCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> primeiraOperacao = executor.submit(() -> executar(schema, () -> {
                Long numero = primeira.get();
                primeiraSessao.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                try {
                    assertThat(liberarCommit.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return numero;
            }));
            int bloqueadora = primeiraSessao.get(10, TimeUnit.SECONDS);
            Future<Long> segundaOperacao = executor.submit(() -> executar(schema, () -> {
                segundaSessao.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return segunda.get();
            }));
            int bloqueada = segundaSessao.get(5, TimeUnit.SECONDS);
            aguardarBloqueioNaSequencia(bloqueadora, bloqueada);
            liberarCommit.countDown();

            Long primeiro = primeiraOperacao.get(10, TimeUnit.SECONDS);
            try {
                return new ResultadoDisputa(primeiro, segundaOperacao.get(10, TimeUnit.SECONDS), null);
            } catch (ExecutionException e) {
                return new ResultadoDisputa(primeiro, null, e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        } finally {
            liberarCommit.countDown();
            executor.shutdownNow();
            aguardarEncerramento(executor);
        }
    }

    private void aguardarBloqueioNaSequencia(int bloqueadora, int bloqueada) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Boolean aguardando = jdbc.queryForObject("""
                    select ? = any(pg_blocking_pids(pid)) and query ilike '%insert into sequencia_lancamento%'
                    from pg_stat_activity where pid = ?
                    """, Boolean.class, bloqueadora, bloqueada);
            if (Boolean.TRUE.equals(aguardando)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        } while (System.nanoTime() < limite);
        throw new AssertionError("A segunda transação não aguardou a primeira na sequência contábil.");
    }

    private void conferirAberturaNumeroUm(Cenario dados) {
        assertThat(jdbc.queryForObject("""
                select data from lancamento_contabil
                where empresa_id = ? and exercicio_id = ? and numero = 1
                  and origem = 'ABERTURA' and status = 'CONFIRMADO'
                """, LocalDate.class, dados.empresa(), dados.exercicio())).isEqualTo(DATA);
    }

    private void conferirRascunho(UUID manual) {
        assertThat(jdbc.queryForObject("""
                select status = 'RASCUNHO' and numero is null and confirmado_por is null and confirmado_em is null
                from lancamento_contabil where id = ?
                """, Boolean.class, manual)).isTrue();
    }

    private long confirmarManual(Cenario dados, UUID id) {
        return lancamentos.confirmarLancamento(dados.empresa(), dados.usuario(), id).getNumero();
    }

    private record ResultadoDisputa(Long primeiro, Long segundo, Throwable erroSegundo) {
    }

    private AberturaConfirmada tentarConcorrente(String schema, Cenario dados,
                                                 CountDownLatch prontas, CountDownLatch largada) {
        try {
            return executar(schema, () -> {
                prontas.countDown();
                try {
                    assertThat(largada.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return abrir(dados);
            });
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("abertura");
            return null;
        }
    }

    private void aguardarEncerramento(ExecutorService executor) {
        try {
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private void conferirVazio(String schema, Cenario dados) {
        executar(schema, () -> {
            conferirPersistencia(dados, 0, 0, null);
            return null;
        });
    }

    private void conferirPersistencia(Cenario dados, int totalLancamentos, int totalPartidas, Long numero) {
        assertThat(jdbc.queryForObject("""
                select count(*) from lancamento_contabil where empresa_id = ? and exercicio_id = ?
                """, Long.class, dados.empresa(), dados.exercicio())).isEqualTo((long) totalLancamentos);
        assertThat(jdbc.queryForObject("""
                select count(*) from partida_contabil p join lancamento_contabil l on l.id = p.lancamento_id
                where l.empresa_id = ? and l.exercicio_id = ?
                """, Long.class, dados.empresa(), dados.exercicio())).isEqualTo((long) totalPartidas);
        List<Long> sequencia = jdbc.queryForList("""
                select ultimo_numero from sequencia_lancamento where empresa_id = ? and exercicio_id = ?
                """, Long.class, dados.empresa(), dados.exercicio());
        assertThat(sequencia).isEqualTo(numero == null ? List.of() : List.of(numero));
    }

    private void rejeitar(String schema, Cenario dados, List<SaldoPorConta> saldos,
                          Class<? extends Throwable> tipo, String mensagem) {
        assertThatThrownBy(() -> executar(schema, () -> lancamentos.registrarSaldosIniciais(pedido(dados, saldos))))
                .isInstanceOf(tipo).hasMessageContaining(mensagem);
    }

    private AberturaConfirmada abrir(Cenario dados) {
        return lancamentos.registrarSaldosIniciais(pedido(dados, saldos(dados)));
    }

    private AberturaContabil pedido(Cenario dados, List<SaldoPorConta> saldos) {
        return new AberturaContabil(dados.empresa(), dados.usuario(), dados.exercicio(), dados.periodo(),
                dados.data(), "Abertura", saldos);
    }

    private List<SaldoPorConta> saldos(Cenario dados) {
        return List.of(debito(dados, VALOR), credito(dados.credito(), VALOR));
    }

    private SaldoPorConta debito(Cenario dados, BigDecimal valor) {
        return new SaldoPorConta(dados.debito(), TipoPartida.DEBITO, valor);
    }

    private SaldoPorConta credito(UUID conta, BigDecimal valor) {
        return new SaldoPorConta(conta, TipoPartida.CREDITO, valor);
    }

    private UUID criarManual(Cenario dados) {
        return criarManual(dados, dados.data());
    }

    private UUID criarManual(Cenario dados, LocalDate data) {
        UUID id = lancamentos.criarRascunho(dados.empresa(), dados.usuario(), dados.exercicio(), dados.periodo(),
                data, "Manual").getId();
        for (SaldoPorConta saldo : saldos(dados)) {
            lancamentos.adicionarPartida(dados.empresa(), dados.usuario(), id, saldo.contaId(),
                    saldo.tipo(), saldo.valor(), null);
        }
        return id;
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa de teste", "Empresa de teste", null);
        em.persist(empresa);

        List<ModeloContaContabil> modelo = modelos.findAllByModeloVersaoOrderByCodigoAsc(1);
        List<ModeloContaContabil> analiticas = modelo.stream()
                .filter(m -> m.getTipo() == TipoConta.ANALITICA && m.getNatureza() == NaturezaConta.DEVEDORA
                        && !m.getContaRedutora()).limit(2).toList();
        ModeloContaContabil sintetica = modelo.stream().filter(m -> m.getTipo() == TipoConta.SINTETICA)
                .findFirst().orElseThrow();
        PlanoContas plano = new PlanoContas(empresa, sintetica.getModeloPlanoContas());
        em.persist(plano);
        UUID debito = persistirConta(plano, analiticas.get(0));
        UUID credito = persistirConta(plano, analiticas.get(1));
        UUID contaSintetica = persistirConta(plano, sintetica);
        return criarExercicio(empresa, usuario.getId(), debito, credito, contaSintetica, DATA);
    }

    private Cenario criarExercicioSeguinte(Cenario anterior) {
        return criarExercicio(em.getReference(Empresa.class, anterior.empresa()), anterior.usuario(),
                anterior.debito(), anterior.credito(), anterior.sintetica(), anterior.data().plusYears(1));
    }

    private Cenario criarExercicio(Empresa empresa, UUID usuario, UUID debito, UUID credito,
                                   UUID sintetica, LocalDate data) {
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) data.getYear(),
                data.withDayOfYear(1), LocalDate.of(data.getYear(), 12, 31));
        em.persist(exercicio);
        YearMonth mes = YearMonth.from(data);
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) mes.getMonthValue(), mes.atDay(1), mes.atEndOfMonth());
        em.persist(periodo);
        em.flush();
        return new Cenario(empresa.getId(), usuario, exercicio.getId(), periodo.getId(), debito, credito, sintetica, data);
    }

    private UUID persistirConta(PlanoContas plano, ModeloContaContabil modelo) {
        ContaContabil conta = new ContaContabil(plano, modelo, null, null);
        em.persist(conta);
        return conta.getId();
    }

    private void comCenario(BiConsumer<String, Cenario> teste) {
        String schema = "lex_abertura_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            teste.accept(schema, executar(schema, this::criarCenario));
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private <T> T executar(String schema, Supplier<T> operacao) {
        TransactionTemplate transacao = new TransactionTemplate(transacoes);
        transacao.setTimeout(20);
        return transacao.execute(status -> {
            jdbc.execute("set local search_path to " + schema);
            jdbc.execute("set local statement_timeout = '12s'");
            return operacao.get();
        });
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo,
                           UUID debito, UUID credito, UUID sintetica, LocalDate data) {
    }
}
