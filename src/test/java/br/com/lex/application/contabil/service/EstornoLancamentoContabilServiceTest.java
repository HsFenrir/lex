package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.LancamentoContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloPlanoContasRepository;
import br.com.lex.domain.contabil.repository.PartidaContabilRepository;
import br.com.lex.domain.contabil.repository.PeriodoContabilRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EstornoLancamentoContabilServiceTest {

    @Autowired private FechamentoContabilService fechamento;

    private static final LocalDate DATA = LocalDate.of(2026, 1, 15);

    @Autowired private LancamentoContabilService service;
    @Autowired private UsuarioRepository usuarios;
    @Autowired private EmpresaRepository empresas;
    @Autowired private ExercicioContabilRepository exercicios;
    @Autowired private PeriodoContabilRepository periodos;
    @Autowired private PlanoContasRepository planos;
    @Autowired private ModeloPlanoContasRepository modelos;
    @Autowired private ModeloContaContabilRepository modelosContas;
    @Autowired private ContaContabilRepository contas;
    @Autowired private LancamentoContabilRepository lancamentos;
    @Autowired private PartidaContabilRepository partidas;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void criaRascunhoComInversaoExataInclusivePartidasRepetidas() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        UUID id = criarEstorno(dados, original);
        entityManager.clear();

        LancamentoContabil estorno = carregar(id);
        assertEquals(OrigemLancamento.ESTORNO, estorno.getOrigem());
        assertEquals(StatusLancamento.RASCUNHO, estorno.getStatus());
        assertEquals(original, estorno.getEstornoDeId());
        assertNull(estorno.getNumero());
        assertNull(estorno.getConfirmadoPor());
        assertNull(estorno.getConfirmadoEm());
        assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
        assertEquals(1L, ultimoNumero(dados, dados.destino()));
        assertEquals(List.of(
                new Linha(dados.contaDebito(), TipoPartida.CREDITO, new BigDecimal("30.00")),
                new Linha(dados.contaDebito(), TipoPartida.CREDITO, new BigDecimal("30.00")),
                new Linha(dados.contaDebito(), TipoPartida.CREDITO, new BigDecimal("40.00")),
                new Linha(dados.contaCredito(), TipoPartida.DEBITO, new BigDecimal("100.00"))
        ), lerLinhas(id).stream().sorted(Comparator.comparing(Linha::valor)).toList());
    }

    @Test
    void rejeitaOriginalEmRascunhoOuEstornadoEEstornoComoOriginal() {
        Cenario dados = criarCenario();
        UUID rascunho = criarRascunho(dados);
        assertThrows(IllegalStateException.class, () -> criarEstorno(dados, rascunho));

        UUID original = criarConfirmado(dados);
        UUID estorno = criarEstorno(dados, original);
        confirmarEstorno(dados, estorno);
        assertThrows(IllegalStateException.class, () -> criarEstorno(dados, original));
        assertThrows(IllegalStateException.class, () -> criarEstorno(dados, estorno));
    }

    @Test
    void rejeitaEstornoDuplicadoEnquantoRascunhoReservaOriginal() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        UUID estorno = criarEstorno(dados, original);
        assertThrows(IllegalStateException.class, () -> criarEstorno(dados, original));
        assertEquals(estorno, estornoReservado(dados, original));
    }

    @Test
    void rejeitaAcessoEIdentificadoresDeOutraEmpresa() {
        Cenario dados = criarCenario();
        Cenario outro = criarCenario();
        UUID original = criarConfirmado(dados);
        UUID estorno = criarEstorno(dados, original);
        assertThrows(IllegalArgumentException.class, () -> service.criarRascunhoEstorno(
                dados.empresa(), outro.usuario(), original, dados.destino().exercicio(), dados.destino().periodo(),
                DATA, "Acesso indevido"));
        assertThrows(IllegalArgumentException.class, () -> criarEstorno(outro, original));
        assertThrows(IllegalArgumentException.class, () -> confirmarEstorno(outro, estorno));
        assertThrows(IllegalArgumentException.class, () -> cancelar(outro, estorno));
        assertThrows(IllegalArgumentException.class,
                () -> service.confirmarEstorno(dados.empresa(), outro.usuario(), estorno));
        assertThrows(IllegalArgumentException.class,
                () -> service.cancelarRascunhoEstorno(dados.empresa(), outro.usuario(), estorno));
        assertEquals(estorno, estornoReservado(dados, original));
    }

    @Test
    void rejeitaDestinoDeOutraEmpresaOuPeriodoDeOutroExercicio() {
        Cenario dados = criarCenario();
        Cenario outro = criarCenario();
        UUID original = criarConfirmado(dados);
        Destino futuro = criarDestino(dados.empresa(), 2027);
        assertThrows(IllegalArgumentException.class, () -> criarEstorno(dados, original, outro.destino(), DATA));
        assertThrows(IllegalArgumentException.class, () -> criarEstorno(dados, original,
                new Destino(futuro.exercicio(), dados.destino().periodo()), DATA.plusYears(1)));
        assertNull(estornoReservado(dados, original));
    }

    @Test
    void rejeitaDestinoEncerradoNaCriacaoERevalidaNaConfirmacao() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        Destino futuro = criarDestino(dados.empresa(), 2027);
        entityManager.flush();
        jdbc.update("UPDATE exercicio_contabil SET status = 'ENCERRADO' WHERE id = ?", futuro.exercicio());
        assertThrows(IllegalStateException.class, () -> criarEstorno(dados, original, futuro, DATA.plusYears(1)));
        jdbc.update("UPDATE exercicio_contabil SET status = 'ABERTO' WHERE id = ?", futuro.exercicio());
        jdbc.update("UPDATE periodo_contabil SET status = 'ENCERRADO' WHERE id = ?", futuro.periodo());
        assertThrows(IllegalStateException.class, () -> criarEstorno(dados, original, futuro, DATA.plusYears(1)));
        jdbc.update("UPDATE periodo_contabil SET status = 'ABERTO' WHERE id = ?", futuro.periodo());
        UUID estorno = criarEstorno(dados, original, futuro, DATA.plusYears(1));
        jdbc.update("UPDATE periodo_contabil SET status = 'ENCERRADO' WHERE id = ?", futuro.periodo());
        assertThrows(IllegalStateException.class, () -> confirmarEstorno(dados, estorno));
        assertNull(carregar(estorno).getNumero());
        cancelar(dados, estorno);
        assertNull(estornoReservado(dados, original));
    }

    @Test
    void rejeitaDataAnteriorAoOriginalOuForaDoPeriodo() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        assertThrows(IllegalArgumentException.class,
                () -> criarEstorno(dados, original, dados.destino(), DATA.minusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> criarEstorno(dados, original, dados.destino(), DATA.plusMonths(1)));
        assertNull(estornoReservado(dados, original));
    }

    @Test
    void confirmaNoExercicioDeDestinoEPreservaTodosOsDadosDoOriginal() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        String cabecalho = cabecalhoSemStatus(original);
        String linhas = partidasOriginaisJson(original);
        Destino futuro = criarDestino(dados.empresa(), 2027);
        periodos.saveAndFlush(new PeriodoContabil(exercicios.getReferenceById(dados.destino().exercicio()),
                (short) 12, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)));
        fechamento.encerrarPeriodo(dados.empresa(), dados.usuario(), dados.destino().exercicio(), dados.destino().periodo());
        CalendarioTestFixture.preencherEncerradosAteNovembro(jdbc, dados.destino().exercicio(), DATA.getMonthValue() + 1);
        fechamento.encerrarExercicio(dados.empresa(), dados.usuario(), dados.destino().exercicio());
        UUID estorno = criarEstorno(dados, original, futuro, DATA.plusYears(1));
        confirmarEstorno(dados, estorno);
        entityManager.clear();

        LancamentoContabil confirmado = carregar(estorno);
        assertEquals(StatusLancamento.CONFIRMADO, confirmado.getStatus());
        assertEquals(2L, confirmado.getNumero());
        assertEquals(futuro.exercicio(), confirmado.getExercicioId());
        assertEquals(dados.usuario(), confirmado.getConfirmadoPor().getId());
        assertNotNull(confirmado.getConfirmadoEm());
        assertEquals(1L, ultimoNumero(dados, dados.destino()));
        assertEquals(2L, ultimoNumero(dados, futuro));
        assertEquals(StatusLancamento.ESTORNADO, carregar(original).getStatus());
        assertEquals(cabecalho, cabecalhoSemStatus(original));
        assertEquals(linhas, partidasOriginaisJson(original));
        assertThrows(IllegalStateException.class, () -> cancelar(dados, estorno));
    }

    @Test
    void bloqueiaMutacoesGenericasDoEstornoEConfirmaComNumeroSubsequente() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        UUID estorno = criarEstorno(dados, original);
        UUID partida = partidas.findAllByLancamentoIdOrderByIdAsc(estorno).getFirst().getId();
        assertThrows(IllegalStateException.class,
                () -> adicionar(dados, estorno, dados.contaDebito(), TipoPartida.DEBITO, "5.00"));
        assertThrows(IllegalStateException.class,
                () -> service.removerPartida(dados.empresa(), dados.usuario(), estorno, partida));
        assertThrows(IllegalStateException.class, () -> service.confirmarLancamento(dados.empresa(), dados.usuario(), estorno));
        assertEquals(4, partidas.countByLancamentoId(estorno));

        assertEquals(2L, confirmarEstorno(dados, estorno).getNumero());
        assertEquals(StatusLancamento.ESTORNADO, carregar(original).getStatus());
    }

    @Test
    void rejeitaAdulteracaoQuePreservaOsTotaisMasAlteraAMultiplicidade() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        UUID estorno = criarEstorno(dados, original);
        List<UUID> repetidas = jdbc.queryForList(
                "SELECT id FROM partida_contabil WHERE lancamento_id = ? AND valor = 30 ORDER BY id",
                UUID.class, estorno);
        assertEquals(2, repetidas.size());
        jdbc.update("UPDATE partida_contabil SET valor = 60 WHERE id = ?", repetidas.get(0));
        jdbc.update("DELETE FROM partida_contabil WHERE id = ?", repetidas.get(1));

        IllegalStateException erro = assertThrows(IllegalStateException.class, () -> confirmarEstorno(dados, estorno));
        assertTrue(erro.getMessage().contains("inversão integral"));
        assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
        assertEquals(StatusLancamento.RASCUNHO, carregar(estorno).getStatus());
        assertEquals(1L, ultimoNumero(dados, dados.destino()));
    }

    @Test
    void contaInativaImpedeCriacaoEConfirmacaoMasPermiteCancelar() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        jdbc.update("UPDATE conta_contabil SET ativa = false WHERE id = ?", dados.contaDebito());
        assertThrows(IllegalStateException.class, () -> criarEstorno(dados, original));
        assertNull(estornoReservado(dados, original));
        jdbc.update("UPDATE conta_contabil SET ativa = true WHERE id = ?", dados.contaDebito());
        UUID estorno = criarEstorno(dados, original);
        jdbc.update("UPDATE conta_contabil SET ativa = false WHERE id = ?", dados.contaDebito());
        assertThrows(IllegalStateException.class, () -> confirmarEstorno(dados, estorno));
        cancelar(dados, estorno);
        assertNull(estornoReservado(dados, original));
        assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
    }

    @Test
    void bloqueiaEstornoContabilDeOrigemOperacional() {
        Cenario dados = criarCenario();
        UUID original = criarRascunho(dados);
        entityManager.flush();
        jdbc.update("UPDATE lancamento_contabil SET origem = 'ESTOQUE' WHERE id = ?", original);
        adicionarPartidasOriginais(dados, original);
        service.confirmarLancamento(dados.empresa(), dados.usuario(), original);
        IllegalStateException erro = assertThrows(IllegalStateException.class, () -> criarEstorno(dados, original));
        assertTrue(erro.getMessage().contains("módulo de origem"));
    }

    @Test
    void cancelaRascunhoLiberaReservaENaoCancelaLancamentoManual() {
        Cenario dados = criarCenario();
        UUID original = criarConfirmado(dados);
        UUID estorno = criarEstorno(dados, original);
        cancelar(dados, estorno);
        entityManager.clear();
        assertFalse(lancamentos.existsById(estorno));
        assertEquals(0, partidas.countByLancamentoId(estorno));
        assertNull(estornoReservado(dados, original));
        assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
        assertEquals(1L, ultimoNumero(dados, dados.destino()));
        assertNotEquals(estorno, criarEstorno(dados, original));

        UUID manual = criarRascunho(dados);
        assertThrows(IllegalArgumentException.class, () -> cancelar(dados, manual));
        assertTrue(lancamentos.existsById(manual));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reverteEstornoESequenciaQuandoTransicaoDoOriginalFalha() {
        comSchemaDescartavel(schema -> {
            Cenario dados = transacaoNoSchema(schema, this::criarCenario);
            UUID original = transacaoNoSchema(schema, () -> criarConfirmado(dados));
            UUID estorno = transacaoNoSchema(schema, () -> {
                UUID id = criarEstorno(dados, original);
                // Provoca falha apenas no segundo UPDATE, depois do flush da confirmação do estorno.
                jdbc.execute("ALTER TABLE lancamento_contabil ADD CONSTRAINT teste_bloquear_estornado CHECK (status <> 'ESTORNADO')");
                return id;
            });

            DataIntegrityViolationException erro = assertThrows(DataIntegrityViolationException.class,
                    () -> transacaoNoSchema(schema, () -> confirmarEstorno(dados, estorno)));
            assertTrue(erro.getMostSpecificCause().getMessage().contains("teste_bloquear_estornado"));
            transacaoNoSchema(schema, () -> {
                assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
                assertEquals(StatusLancamento.RASCUNHO, carregar(estorno).getStatus());
                assertNull(carregar(estorno).getNumero());
                assertNull(carregar(estorno).getConfirmadoPor());
                assertNull(carregar(estorno).getConfirmadoEm());
                assertEquals(1L, ultimoNumero(dados, dados.destino()));
                assertEquals(estorno, estornoReservado(dados, original));
                return null;
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void apenasUmaCriacaoConcorrenteReservaOOriginal() {
        comSchemaDescartavel(schema -> {
            Cenario dados = transacaoNoSchema(schema, this::criarCenario);
            UUID original = transacaoNoSchema(schema, () -> criarConfirmado(dados));
            List<Resultado> resultados = concorrer(schema,
                    () -> criarEstorno(dados, original), () -> criarEstorno(dados, original));
            UUID estorno = unicaVitoria(resultados);
            assertEquals("O lançamento original já possui um estorno associado.", unicaFalha(resultados).getMessage());
            transacaoNoSchema(schema, () -> {
                assertEquals(estorno, estornoReservado(dados, original));
                assertEquals(StatusLancamento.RASCUNHO, carregar(estorno).getStatus());
                assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
                assertEquals(4, partidas.countByLancamentoId(estorno));
                return null;
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void apenasUmaConfirmacaoConcorrenteDoEstornoConsomeNumero() {
        comEstornoPersistido((schema, dados, original, estorno) -> {
            List<Resultado> resultados = concorrer(schema,
                    () -> confirmarEstorno(dados, estorno).getId(), () -> confirmarEstorno(dados, estorno).getId());
            assertEquals(estorno, unicaVitoria(resultados));
            assertEquals("O estorno deve estar em rascunho.", unicaFalha(resultados).getMessage());
            transacaoNoSchema(schema, () -> {
                assertEquals(StatusLancamento.ESTORNADO, carregar(original).getStatus());
                assertEquals(StatusLancamento.CONFIRMADO, carregar(estorno).getStatus());
                assertEquals(2L, carregar(estorno).getNumero());
                assertEquals(2L, ultimoNumero(dados, dados.destino()));
                return null;
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmacaoECancelamentoConcorrentesTemUmUnicoVencedor() {
        comEstornoPersistido((schema, dados, original, estorno) -> {
            List<Resultado> resultados = concorrer(schema, () -> confirmarEstorno(dados, estorno).getId(), () -> {
                cancelar(dados, estorno);
                return original;
            });
            UUID vencedor = unicaVitoria(resultados);
            RuntimeException rejeicao = unicaFalha(resultados);
            transacaoNoSchema(schema, () -> {
                if (vencedor.equals(estorno)) {
                    assertEquals("O estorno deve estar em rascunho.", rejeicao.getMessage());
                    assertEquals(StatusLancamento.ESTORNADO, carregar(original).getStatus());
                    assertEquals(StatusLancamento.CONFIRMADO, carregar(estorno).getStatus());
                    assertEquals(2L, ultimoNumero(dados, dados.destino()));
                } else {
                    assertEquals(original, vencedor);
                    assertEquals("Lançamento não encontrado na empresa.", rejeicao.getMessage());
                    assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
                    assertFalse(lancamentos.existsById(estorno));
                    assertEquals(0, partidas.countByLancamentoId(estorno));
                    assertNull(estornoReservado(dados, original));
                    assertEquals(1L, ultimoNumero(dados, dados.destino()));
                }
                return null;
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cancelamentoConcorrenteComCriacaoNaoDeixaReservaDuplicada() {
        comEstornoPersistido((schema, dados, original, estorno) -> {
            List<Resultado> resultados = concorrer(schema, () -> {
                cancelar(dados, estorno);
                return estorno;
            }, () -> criarEstorno(dados, original));
            assertEquals(estorno, resultados.get(0).id());
            assertNull(resultados.get(0).falha());
            Resultado criacao = resultados.get(1);
            transacaoNoSchema(schema, () -> {
                assertFalse(lancamentos.existsById(estorno));
                assertEquals(StatusLancamento.CONFIRMADO, carregar(original).getStatus());
                if (criacao.falha() == null) {
                    assertNotNull(criacao.id());
                    assertEquals(criacao.id(), estornoReservado(dados, original));
                } else {
                    assertEquals("O lançamento original já possui um estorno associado.", criacao.falha().getMessage());
                    assertNull(estornoReservado(dados, original));
                    assertEquals(criarEstorno(dados, original), estornoReservado(dados, original));
                }
                return null;
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void estornoEConfirmacaoManualConcorrentesUsamAMesmaSequencia() {
        comEstornoPersistido((schema, dados, original, estorno) -> {
            UUID manual = transacaoNoSchema(schema, () -> {
                UUID id = criarRascunho(dados);
                adicionarPartidasOriginais(dados, id);
                return id;
            });
            List<Resultado> resultados = concorrer(schema, () -> confirmarEstorno(dados, estorno).getId(),
                    () -> service.confirmarLancamento(dados.empresa(), dados.usuario(), manual).getId());
            assertTrue(resultados.stream().allMatch(resultado -> resultado.falha() == null));
            transacaoNoSchema(schema, () -> {
                assertEquals(StatusLancamento.ESTORNADO, carregar(original).getStatus());
                assertEquals(StatusLancamento.CONFIRMADO, carregar(estorno).getStatus());
                assertEquals(StatusLancamento.CONFIRMADO, carregar(manual).getStatus());
                assertEquals(Set.of(2L, 3L), Set.of(carregar(estorno).getNumero(), carregar(manual).getNumero()));
                assertEquals(3L, ultimoNumero(dados, dados.destino()));
                return null;
            });
        });
    }

    private List<Resultado> concorrer(String schema, Supplier<UUID> primeira, Supplier<UUID> segunda) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch prontas = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            Future<Resultado> a = executor.submit(() -> executarConcorrente(schema, primeira, prontas, largada));
            Future<Resultado> b = executor.submit(() -> executarConcorrente(schema, segunda, prontas, largada));
            aguardar(prontas);
            largada.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Teste concorrente interrompido.", erro);
        } catch (ExecutionException | TimeoutException erro) {
            throw new AssertionError("A operação concorrente falhou ou excedeu o prazo.", erro);
        } finally {
            largada.countDown();
            executor.shutdownNow();
            try {
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS), "Transações concorrentes ainda ativas.");
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
                throw new AssertionError("A espera pelas transações foi interrompida.", erro);
            }
        }
    }

    private Resultado executarConcorrente(String schema, Supplier<UUID> operacao,
                                          CountDownLatch prontas, CountDownLatch largada) {
        try {
            return new Resultado(transacaoNoSchema(schema, () -> {
                prontas.countDown();
                aguardar(largada);
                return operacao.get();
            }), null);
        } catch (IllegalArgumentException | IllegalStateException erro) {
            return new Resultado(null, erro);
        }
    }

    private static void aguardar(CountDownLatch barreira) {
        try {
            assertTrue(barreira.await(10, TimeUnit.SECONDS), "As threads não se encontraram na barreira.");
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Barreira interrompida.", erro);
        }
    }

    private static UUID unicaVitoria(List<Resultado> resultados) {
        List<Resultado> vitorias = resultados.stream().filter(resultado -> resultado.falha() == null).toList();
        assertEquals(1, vitorias.size());
        return vitorias.getFirst().id();
    }

    private static RuntimeException unicaFalha(List<Resultado> resultados) {
        List<Resultado> falhas = resultados.stream().filter(resultado -> resultado.falha() != null).toList();
        assertEquals(1, falhas.size());
        return falhas.getFirst().falha();
    }

    private void comEstornoPersistido(CenarioEstorno teste) {
        comSchemaDescartavel(schema -> {
            Cenario dados = transacaoNoSchema(schema, this::criarCenario);
            UUID original = transacaoNoSchema(schema, () -> criarConfirmado(dados));
            UUID estorno = transacaoNoSchema(schema, () -> criarEstorno(dados, original));
            teste.executar(schema, dados, original, estorno);
        });
    }

    private void comSchemaDescartavel(Consumer<String> teste) {
        assertEquals("lex_test|lex_test_runner", jdbc.queryForObject(
                "SELECT current_database() || '|' || current_user", String.class));
        String schema = "lex_estorno_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            teste.accept(schema);
        } finally {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '5s'");
                jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            });
        }
    }

    private <T> T transacaoNoSchema(String schema, Supplier<T> operacao) {
        TransactionTemplate transacao = new TransactionTemplate(transactionManager);
        transacao.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return transacao.execute(status -> {
            jdbc.execute("SET LOCAL search_path TO " + schema);
            jdbc.execute("SET LOCAL lock_timeout = '8s'");
            jdbc.execute("SET LOCAL statement_timeout = '12s'");
            return operacao.get();
        });
    }

    private Cenario criarCenario() {
        Usuario usuario = usuarios.save(new Usuario("Usuário teste", UUID.randomUUID() + "@example.test", "hash-teste"));
        Empresa empresa = empresas.save(new Empresa(usuario, "Empresa de teste", "Empresa de teste", null));
        PlanoContas plano = planos.save(new PlanoContas(empresa, modelos.findById(1).orElseThrow()));
        List<ModeloContaContabil> modelosAnaliticos = modelosContas.findAllByModeloVersaoOrderByCodigoAsc(1).stream()
                .filter(modelo -> modelo.getTipo() == TipoConta.ANALITICA && !modelo.getContaRedutora())
                .limit(2).toList();
        UUID debito = contas.save(new ContaContabil(plano, modelosAnaliticos.get(0), null, null)).getId();
        UUID credito = contas.save(new ContaContabil(plano, modelosAnaliticos.get(1), null, null)).getId();
        return new Cenario(usuario.getId(), empresa.getId(), debito, credito, criarDestino(empresa.getId(), 2026));
    }

    private Destino criarDestino(UUID empresa, int ano) {
        ExercicioContabil exercicio = exercicios.save(new ExercicioContabil(empresas.getReferenceById(empresa),
                (short) ano, LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31)));
        PeriodoContabil periodo = periodos.save(new PeriodoContabil(exercicio, (short) 1,
                LocalDate.of(ano, 1, 1), LocalDate.of(ano, 1, 31)));
        return new Destino(exercicio.getId(), periodo.getId());
    }

    private UUID criarRascunho(Cenario dados) {
        return service.criarRascunho(dados.empresa(), dados.usuario(), dados.destino().exercicio(),
                dados.destino().periodo(), DATA, "Original de teste").getId();
    }

    private UUID criarConfirmado(Cenario dados) {
        UUID id = criarRascunho(dados);
        adicionarPartidasOriginais(dados, id);
        return service.confirmarLancamento(dados.empresa(), dados.usuario(), id).getId();
    }

    private void adicionarPartidasOriginais(Cenario dados, UUID id) {
        adicionar(dados, id, dados.contaDebito(), TipoPartida.DEBITO, "30.00");
        adicionar(dados, id, dados.contaDebito(), TipoPartida.DEBITO, "30.00");
        adicionar(dados, id, dados.contaDebito(), TipoPartida.DEBITO, "40.00");
        adicionar(dados, id, dados.contaCredito(), TipoPartida.CREDITO, "100.00");
    }

    private void adicionar(Cenario dados, UUID id, UUID conta, TipoPartida tipo, String valor) {
        service.adicionarPartida(dados.empresa(), dados.usuario(), id, conta, tipo, new BigDecimal(valor), null);
    }

    private UUID criarEstorno(Cenario dados, UUID original) {
        return criarEstorno(dados, original, dados.destino(), DATA);
    }

    private UUID criarEstorno(Cenario dados, UUID original, Destino destino, LocalDate data) {
        return service.criarRascunhoEstorno(dados.empresa(), dados.usuario(), original, destino.exercicio(),
                destino.periodo(), data, "Estorno de teste").getId();
    }

    private LancamentoContabil confirmarEstorno(Cenario dados, UUID id) {
        return service.confirmarEstorno(dados.empresa(), dados.usuario(), id);
    }

    private void cancelar(Cenario dados, UUID id) {
        service.cancelarRascunhoEstorno(dados.empresa(), dados.usuario(), id);
    }

    private LancamentoContabil carregar(UUID id) {
        return lancamentos.findById(id).orElseThrow();
    }

    private UUID estornoReservado(Cenario dados, UUID original) {
        return lancamentos.findByEstornoDeIdAndEmpresaId(original, dados.empresa())
                .map(LancamentoContabil::getId).orElse(null);
    }

    private List<Linha> lerLinhas(UUID id) {
        return partidas.findAllProjetadasByLancamentoIdOrderByIdAsc(id).stream()
                .map(partida -> new Linha(partida.getContaContabilId(), partida.getTipo(), partida.getValor())).toList();
    }

    private String cabecalhoSemStatus(UUID id) {
        return jdbc.queryForObject("SELECT (to_jsonb(l) - 'status')::text FROM lancamento_contabil l WHERE id = ?",
                String.class, id);
    }

    private String partidasOriginaisJson(UUID id) {
        return jdbc.queryForObject("""
                SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id)::text
                FROM partida_contabil p WHERE p.lancamento_id = ?
                """, String.class, id);
    }

    private long ultimoNumero(Cenario dados, Destino destino) {
        return jdbc.queryForObject("SELECT ultimo_numero FROM sequencia_lancamento WHERE empresa_id = ? AND exercicio_id = ?",
                Long.class, dados.empresa(), destino.exercicio());
    }

    private record Destino(UUID exercicio, UUID periodo) { }
    private record Cenario(UUID usuario, UUID empresa, UUID contaDebito, UUID contaCredito, Destino destino) { }
    private record Linha(UUID conta, TipoPartida tipo, BigDecimal valor) { }
    private record Resultado(UUID id, RuntimeException falha) { }

    @FunctionalInterface
    private interface CenarioEstorno {
        void executar(String schema, Cenario cenario, UUID original, UUID estorno);
    }
}
