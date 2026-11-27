package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.service.EstoqueService.Contexto;
import br.com.lex.application.contabil.service.EstoqueService.Ficha;
import br.com.lex.application.contabil.service.EstoqueService.Saida;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Alocacao;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Lote;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Movimento;
import br.com.lex.domain.contabil.repository.MovimentoEstoqueRepository.Tipo;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class EstoqueServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 5, 15);
    private static final String CAIXA = "1.1.01.001";
    private static final String ESTOQUE = "1.1.03.001";
    private static final String RECEITA = "4.1.001";
    private static final String CMV = "5.1.001";

    @Autowired private EstoqueService estoque;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private PlanoContasService planos;
    @Autowired private ContaContabilRepository contas;
    @Autowired private FechamentoContabilService fechamento;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transacoes;

    @Test
    @Transactional
    void cadastraEIsolaProdutosPorEmpresa() {
        Cenario c = criarCenario();
        Cenario outra = criarCenario();
        EstoqueService.Produto produto = estoque.listarProdutos(c.empresa(), c.usuario()).getFirst();
        assertThat(produto.codigo()).isEqualTo("SKU-01");
        assertThat(produto.unidade()).isEqualTo("UN");
        assertThat(produto.ativo()).isTrue();
        assertThat(estoque.listarProdutos(outra.empresa(), outra.usuario()))
                .extracting(EstoqueService.Produto::id).containsExactly(outra.produto());
        assertThatThrownBy(() -> estoque.consultar(c.empresa(), outra.usuario(), c.produto()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não pertence");
        assertThatThrownBy(() -> estoque.consultar(outra.empresa(), outra.usuario(), c.produto()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Produto");
        assertThatThrownBy(() -> estoque.registrarEntrada(
                new Contexto(outra.empresa(), outra.usuario(), outra.exercicio(), outra.periodo(), c.produto(), DATA),
                BigDecimal.ONE, BigDecimal.ONE)).hasMessageContaining("Produto");
        assertThatThrownBy(() -> estoque.cadastrarProduto(c.empresa(), c.usuario(), "sku-01", "Repetido", "UN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void consomeLotesIntegraisEParciaisNaMesmaSaidaEmOrdemPeps() {
        Cenario c = criarCenario();
        Movimento primeiro = entrada(c, DATA, "3", "2.30");
        Movimento segundo = entrada(c, DATA, "2", "4.10");
        Movimento terceiro = entrada(c, DATA.plusDays(1), "4", "8.00");
        Saida saida = saida(c, DATA.plusDays(1), "4");

        assertThat(saida.movimento().valorTotal()).isEqualByComparingTo("11.00");
        assertThat(saida.alocacoes()).extracting(Alocacao::entradaId).containsExactly(primeiro.id(), segundo.id());
        conferirAlocacao(saida.alocacoes().get(0), "3", "6.90");
        conferirAlocacao(saida.alocacoes().get(1), "1", "4.10");
        Ficha ficha = ficha(c);
        assertThat(ficha.movimentos()).extracting(Movimento::id)
                .containsExactly(primeiro.id(), segundo.id(), terceiro.id(), saida.movimento().id());
        assertThat(ficha.movimentos()).allSatisfy(m -> {
            assertThat(m.lancamentoId()).isNotNull();
            assertThat(m.usuarioId()).isEqualTo(c.usuario());
        });
        assertThat(ficha.saldo().quantidade()).isEqualByComparingTo("5");
        assertThat(ficha.saldo().valor()).isEqualByComparingTo("36.10");
        assertThat(ficha.lotes()).extracting(Lote::entradaId).containsExactly(primeiro.id(), segundo.id(), terceiro.id());
        assertThat(ficha.lotes().getFirst().quantidadeRestante()).isEqualByComparingTo("0");
        assertThat(ficha.lotes().getFirst().valorRestante()).isEqualByComparingTo("0");
        assertThat(ficha.lotes().get(1).custoUnitario()).isEqualByComparingTo("4.10");
        assertThat(ficha.lotes().get(1).quantidadeRestante()).isEqualByComparingTo("1");
        assertThat(ficha.lotes().get(1).valorRestante()).isEqualByComparingTo("4.10");
        assertThat(estoque.consultarAlocacoes(c.empresa(), c.usuario(), c.produto(), saida.movimento().id()))
                .isEqualTo(saida.alocacoes());
    }

    @Test
    @Transactional
    void rateiaCentavosCumulativamenteERejeitaPerdaDePrecisao() {
        Cenario c = criarCenario();
        Movimento entrada = entrada(c, DATA, "0.000003", "5000.00000000");
        assertThat(entrada.valorTotal()).isEqualByComparingTo("0.02");
        Saida primeira = saida(c, DATA, "0.000001");
        Saida segunda = saida(c, DATA, "0.000001");
        Saida ultima = saida(c, DATA, "0.000001");
        conferirAlocacao(primeira.alocacoes().getFirst(), "0.000001", "0.01");
        conferirAlocacao(segunda.alocacoes().getFirst(), "0.000001", "0");
        conferirAlocacao(ultima.alocacoes().getFirst(), "0.000001", "0.01");
        assertThat(ficha(c).saldo().valor()).isEqualByComparingTo("0");
        assertThat(ficha(c).saldo().quantidade()).isEqualByComparingTo("0");

        assertThatThrownBy(() -> entrada(c, DATA, "0.0000001", "5000")).hasMessageContaining("escala");
        assertThatThrownBy(() -> entrada(c, DATA, "1", "1.000000001")).hasMessageContaining("escala");
        assertThatThrownBy(() -> entrada(c, DATA, "10000000000000", "1")).hasMessageContaining("precisão");
        assertThatThrownBy(() -> entrada(c, DATA, "1", "0.00000001")).hasMessageContaining("centavo");
        assertThatThrownBy(() -> saida(c, DATA, "0")).hasMessageContaining("positivo");
        assertThat(ficha(c).movimentos()).hasSize(4);
    }

    @Test
    @Transactional
    void preservaSaldoEAlocacoesAposSaidaInsuficienteEImpedeRetroatividade() {
        Cenario c = criarCenario();
        entrada(c, DATA, "2", "1.00");
        entrada(c, DATA.plusDays(2), "3", "2.00");
        Ficha antes = ficha(c);
        assertThatThrownBy(() -> saida(c, DATA.plusDays(2), "6")).hasMessageContaining("insuficiente");
        assertThatThrownBy(() -> entrada(c, DATA.plusDays(1), "1", "99.00")).hasMessageContaining("última movimentação");
        assertThatThrownBy(() -> saida(c, DATA.plusDays(1), "1")).hasMessageContaining("última movimentação");
        assertThat(ficha(c)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from alocacao_estoque where produto_id = ?", Long.class, c.produto()))
                .isZero();
    }

    @Test
    @Transactional
    void respeitaAberturaCalendarioEProdutoInativo() {
        Cenario c = criarCenario();
        lancamentos.registrarSaldosIniciais(new AberturaContabil(c.empresa(), c.usuario(), c.exercicio(), c.periodo(),
                DATA, "Migração", List.of(partida(c, CAIXA, TipoPartida.DEBITO, BigDecimal.ONE),
                        partida(c, "3.1.001", TipoPartida.CREDITO, BigDecimal.ONE))));
        assertThatThrownBy(() -> entrada(c, DATA.minusDays(1), "1", "1")).hasMessageContaining("anterior à abertura");
        assertThatThrownBy(() -> entrada(c, DATA.plusMonths(1), "1", "1")).hasMessageContaining("dentro do período");
        entrada(c, DATA, "1", "1");
        estoque.alterarAtividade(c.empresa(), c.usuario(), c.produto(), false);
        assertThatThrownBy(() -> saida(c, DATA, "1")).hasMessageContaining("produto deve estar ativo");
        estoque.alterarAtividade(c.empresa(), c.usuario(), c.produto(), true);
        fechamento.encerrarPeriodo(c.empresa(), c.usuario(), c.exercicio(), c.periodo());
        assertThatThrownBy(() -> entrada(c, DATA, "1", "1")).hasMessageContaining("período deve estar aberto");
        assertThat(ficha(c).saldo().quantidade()).isEqualByComparingTo("1");
    }

    @Test
    void exigeTransacaoComLancamentoEstoqueConfirmadoEReverteConsumoAposFalha() {
        comCenario((schema, c) -> {
            Contexto contexto = contexto(c, DATA);
            assertThatThrownBy(() -> estoque.registrarEntrada(contexto, BigDecimal.ONE, BigDecimal.ONE))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> executar(schema, () -> estoque.registrarEntrada(contexto, BigDecimal.ONE, BigDecimal.ONE)))
                    .isInstanceOfSatisfying(DataIntegrityViolationException.class, erro ->
                            assertThat(erro.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, causa -> {
                                assertThat(causa.getSQLState()).isEqualTo("23514");
                                assertThat(causa.getServerErrorMessage()).isNotNull();
                                assertThat(causa.getServerErrorMessage().getConstraint())
                                        .isEqualTo("ck_movimento_estoque_contabilizado");
                            }));
            assertThat(executar(schema, () -> ficha(c)).movimentos()).isEmpty();

            executar(schema, () -> entrada(c, DATA, "5", "3.00"));
            Ficha original = executar(schema, () -> ficha(c));
            assertThatThrownBy(() -> executar(schema, () -> {
                estoque.registrarSaida(contexto, new BigDecimal("4"));
                throw new IllegalStateException("Falha simulada na contabilização da saída.");
            })).hasMessageContaining("Falha simulada");
            assertThat(executar(schema, () -> ficha(c))).isEqualTo(original);
            assertThat(executar(schema, () -> jdbc.queryForObject(
                    "select count(*) from alocacao_estoque", Long.class))).isZero();
        });
    }

    @Test
    void duasSaidasConcorrentesNaoConsomemAsMesmasUnidades() {
        comCenario((schema, c) -> {
            executar(schema, () -> entrada(c, DATA, "5", "3.00"));
            var executor = Executors.newFixedThreadPool(2);
            var pidPrimeira = new CompletableFuture<Integer>();
            var pidSegunda = new CompletableFuture<Integer>();
            var liberarCommit = new CountDownLatch(1);
            try {
                var primeira = executor.submit(() -> executar(schema, () -> {
                    Saida saida = saida(c, DATA, "4");
                    pidPrimeira.complete(pid());
                    aguardar(liberarCommit);
                    return saida;
                }));
                int bloqueadora = pidPrimeira.get(10, TimeUnit.SECONDS);
                var segunda = executor.submit(() -> executar(schema, () -> {
                    pidSegunda.complete(pid());
                    return saida(c, DATA, "4");
                }));
                aguardarBloqueio(bloqueadora, pidSegunda.get(5, TimeUnit.SECONDS));
                liberarCommit.countDown();

                assertThat(primeira.get(10, TimeUnit.SECONDS).movimento().valorTotal()).isEqualByComparingTo("12.00");
                assertThatThrownBy(() -> segunda.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasRootCauseMessage("Estoque insuficiente para a saída.");
                Ficha finalizada = executar(schema, () -> ficha(c));
                assertThat(finalizada.movimentos()).hasSize(2);
                assertThat(finalizada.saldo().quantidade()).isEqualByComparingTo("1");
                assertThat(finalizada.saldo().valor()).isEqualByComparingTo("3.00");
                assertThat(executar(schema, () -> jdbc.queryForObject(
                        "select count(*) from alocacao_estoque", Long.class))).isEqualTo(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new AssertionError(e);
            } finally {
                liberarCommit.countDown();
                executor.shutdownNow();
                try {
                    assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        });
    }

    @Test
    void aplicaV8SobreV7() {
        comSchema(schema -> {
            migrar(schema, "7");
            migrar(schema, null);
            executar(schema, () -> {
                Cenario c = criarCenario();
                assertThat(ficha(c).saldo().quantidade()).isEqualByComparingTo("0");
                assertThat(jdbc.queryForObject("""
                        select count(*) from flyway_schema_history where version = '8' and success
                        """, Long.class)).isEqualTo(1L);
                return null;
            });
        });
    }

    private Movimento entrada(Cenario c, LocalDate data, String quantidade, String custo) {
        Movimento entrada = estoque.registrarEntrada(contexto(c, data), new BigDecimal(quantidade), new BigDecimal(custo));
        vincularFixture(c, entrada);
        return entrada;
    }

    private Saida saida(Cenario c, LocalDate data, String quantidade) {
        Saida saida = estoque.registrarSaida(contexto(c, data), new BigDecimal(quantidade));
        vincularFixture(c, saida.movimento());
        return saida;
    }

    // Fixture que satisfaz o vínculo obrigatório sem expor no 7A um fluxo de compra ou venda.
    private void vincularFixture(Cenario c, Movimento movimento) {
        BigDecimal custo = movimento.valorTotal();
        List<SaldoPorConta> partidas = movimento.tipo() == Tipo.ENTRADA
                ? List.of(partida(c, ESTOQUE, TipoPartida.DEBITO, custo), partida(c, CAIXA, TipoPartida.CREDITO, custo))
                : custo.signum() == 0
                    ? List.of(partida(c, CAIXA, TipoPartida.DEBITO, BigDecimal.ONE),
                            partida(c, RECEITA, TipoPartida.CREDITO, BigDecimal.ONE))
                    : List.of(partida(c, CMV, TipoPartida.DEBITO, custo), partida(c, ESTOQUE, TipoPartida.CREDITO, custo));
        UUID lancamento = lancamentos.escriturarTecnico(em.getReference(Empresa.class, c.empresa()),
                em.getReference(Usuario.class, c.usuario()), em.getReference(ExercicioContabil.class, c.exercicio()),
                em.getReference(PeriodoContabil.class, c.periodo()), movimento.data(), "Fixture de estoque",
                OrigemLancamento.ESTOQUE, partidas).getId();
        estoque.vincularLancamento(movimento, lancamento);
    }

    private SaldoPorConta partida(Cenario c, String codigo, TipoPartida tipo, BigDecimal valor) {
        return new SaldoPorConta(c.contas().get(codigo), tipo, valor);
    }

    private Ficha ficha(Cenario c) {
        return estoque.consultar(c.empresa(), c.usuario(), c.produto());
    }

    private Contexto contexto(Cenario c, LocalDate data) {
        return new Contexto(c.empresa(), c.usuario(), c.exercicio(), c.periodo(), c.produto(), data);
    }

    private void conferirAlocacao(Alocacao alocacao, String quantidade, String valor) {
        assertThat(alocacao.quantidade()).isEqualByComparingTo(quantidade);
        assertThat(alocacao.valor()).isEqualByComparingTo(valor);
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Loja", "Loja", null);
        em.persist(empresa);
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) 2026,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        em.persist(exercicio);
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) 5, DATA.withDayOfMonth(1), DATA.withDayOfMonth(31));
        em.persist(periodo);
        em.flush();
        UUID plano = planos.provisionar(empresa.getId(), usuario.getId()).getId();
        UUID produto = estoque.cadastrarProduto(empresa.getId(), usuario.getId(), " sku-01 ", "Mercadoria", " un ").id();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), periodo.getId(), produto,
                contas.findAllByPlanoContasIdOrderByCodigoAsc(plano).stream()
                        .collect(toMap(c -> c.getCodigo(), c -> c.getId())));
    }

    private void comCenario(BiConsumer<String, Cenario> teste) {
        comSchema(schema -> {
            migrar(schema, null);
            teste.accept(schema, executar(schema, this::criarCenario));
        });
    }

    private void comSchema(Consumer<String> teste) {
        String schema = "lex_estoque_" + UUID.randomUUID().toString().replace("-", "");
        try {
            teste.accept(schema);
        } finally {
            jdbc.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private void migrar(String schema, String alvo) {
        var configuracao = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (alvo != null) {
            configuracao.target(alvo);
        }
        configuracao.load().migrate();
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

    private void aguardarBloqueio(int bloqueadora, int bloqueada) throws InterruptedException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limite) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select ? = any(pg_blocking_pids(pid)) and query ilike '%produto_estoque%'
                    from pg_stat_activity where pid = ?
                    """, Boolean.class, bloqueadora, bloqueada))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("A segunda saída não aguardou no bloqueio do produto.");
    }

    private void aguardar(CountDownLatch liberar) {
        try {
            assertThat(liberar.await(12, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo, UUID produto,
                           Map<String, UUID> contas) {
    }
}
