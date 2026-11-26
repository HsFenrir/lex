package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.LancamentoContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ExercicioContabilRepository;
import br.com.lex.domain.contabil.repository.LancamentoContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloPlanoContasRepository;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
class ConfirmacaoLancamentoContabilServiceTest {

    private static final BigDecimal VALOR = new BigDecimal("100.00");

    @Autowired
    private LancamentoContabilService service;
    @Autowired
    private UsuarioRepository usuarios;
    @Autowired
    private EmpresaRepository empresas;
    @Autowired
    private ExercicioContabilRepository exercicios;
    @Autowired
    private PeriodoContabilRepository periodos;
    @Autowired
    private ModeloPlanoContasRepository modelos;
    @Autowired
    private ModeloContaContabilRepository modelosContas;
    @Autowired
    private PlanoContasRepository planos;
    @Autowired
    private ContaContabilRepository contas;
    @Autowired
    private LancamentoContabilRepository lancamentos;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void confirmaComPrimeiroNumeroUsuarioHorarioENumeroSubsequente() {
        Cenario dados = criarCenario();
        UUID primeiro = criarBalanceado(dados, VALOR);
        UUID segundo = criarBalanceado(dados, VALOR);
        OffsetDateTime antes = OffsetDateTime.now().minusSeconds(1);

        confirmar(dados, primeiro);
        confirmar(dados, segundo);
        entityManager.clear();

        LancamentoContabil confirmado = lancamentos.findById(primeiro).orElseThrow();
        assertEquals(StatusLancamento.CONFIRMADO, confirmado.getStatus());
        assertEquals(1L, confirmado.getNumero());
        assertEquals(dados.usuarioId(), confirmado.getConfirmadoPor().getId());
        assertTrue(confirmado.getConfirmadoEm().isAfter(antes));
        assertTrue(confirmado.getConfirmadoEm().isBefore(OffsetDateTime.now().plusSeconds(1)));
        assertEquals(2L, lancamentos.findById(segundo).orElseThrow().getNumero());
        assertEquals(2L, ultimoNumero(dados));
    }

    @Test
    void mantemSequenciasIndependentesPorEmpresaEExercicio() {
        Cenario original = criarCenario();
        Cenario outraEmpresa = criarCenario();
        Cenario outroExercicio = criarExercicio(original.usuarioId(), original.empresaId(), original.contaId(), 2027);

        assertEquals(1L, confirmar(original, criarBalanceado(original, VALOR)).getNumero());
        assertEquals(1L, confirmar(outraEmpresa, criarBalanceado(outraEmpresa, VALOR)).getNumero());
        assertEquals(1L, confirmar(outroExercicio, criarBalanceado(outroExercicio, VALOR)).getNumero());
        assertEquals(2L, confirmar(original, criarBalanceado(original, VALOR)).getNumero());
        assertEquals(1L, ultimoNumero(outraEmpresa));
        assertEquals(1L, ultimoNumero(outroExercicio));
    }

    @Test
    void rejeitaUsuarioNaoAutorizadoEIdDeOutraEmpresa() {
        Cenario dados = criarCenario();
        Cenario outro = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        assertThrows(IllegalArgumentException.class,
                () -> service.confirmarLancamento(dados.empresaId(), outro.usuarioId(), id));
        assertThrows(IllegalArgumentException.class, () -> confirmar(outro, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaUsuarioInativo() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        alterarEstado("UPDATE usuario SET ativo = false WHERE id = ?", dados.usuarioId());
        assertThrows(IllegalStateException.class, () -> confirmar(dados, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaEmpresaInativa() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        alterarEstado("UPDATE empresa SET ativa = false WHERE id = ?", dados.empresaId());
        assertThrows(IllegalStateException.class, () -> confirmar(dados, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaExercicioEncerrado() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        alterarEstado("UPDATE exercicio_contabil SET status = 'ENCERRADO' WHERE id = ?", dados.exercicioId());
        assertThrows(IllegalStateException.class, () -> confirmar(dados, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaPeriodoEncerrado() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        alterarEstado("UPDATE periodo_contabil SET status = 'ENCERRADO' WHERE id = ?", dados.periodoId());
        assertThrows(IllegalStateException.class, () -> confirmar(dados, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaDataContabilForaDoPeriodo() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        alterarEstado("UPDATE lancamento_contabil SET data = data + 31 WHERE id = ?", id);
        assertThrows(IllegalArgumentException.class, () -> confirmar(dados, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaRascunhoSemPartidasOuComApenasUmaPartida() {
        Cenario dados = criarCenario();
        UUID vazio = criarRascunho(dados);
        UUID incompleto = criarRascunho(dados);
        adicionar(dados, incompleto, TipoPartida.DEBITO, VALOR);

        assertThrows(IllegalStateException.class, () -> confirmar(dados, vazio));
        assertThrows(IllegalStateException.class, () -> confirmar(dados, incompleto));
        verificarRascunhoSemSequencia(dados, vazio);
        verificarRascunhoSemSequencia(dados, incompleto);
    }

    @Test
    void rejeitaRascunhoDesequilibrado() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        adicionar(dados, id, TipoPartida.DEBITO, BigDecimal.ONE);
        assertThrows(IllegalStateException.class, () -> confirmar(dados, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaContaDesativadaDepoisDaInclusaoDasPartidas() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        alterarEstado("UPDATE conta_contabil SET ativa = false WHERE id = ?", dados.contaId());
        assertThrows(IllegalStateException.class, () -> confirmar(dados, id));
        verificarRascunhoSemSequencia(dados, id);
    }

    @Test
    void rejeitaNovaConfirmacaoSemConsumirNumero() {
        Cenario dados = criarCenario();
        UUID id = criarBalanceado(dados, VALOR);
        confirmar(dados, id);

        assertThrows(IllegalStateException.class, () -> confirmar(dados, id));
        assertEquals(1L, ultimoNumero(dados));
    }

    @Test
    void rejeitaConfirmacaoGenericaDeEstornoMesmoComPartidasValidas() {
        Cenario dados = criarCenario();
        LancamentoContabil original = confirmar(dados, criarBalanceado(dados, VALOR));
        LancamentoContabil estorno = service.criarRascunhoEstorno(dados.empresaId(), dados.usuarioId(),
                original.getId(), dados.exercicioId(), dados.periodoId(), dados.data(), "Estorno de teste");

        IllegalStateException erro = assertThrows(IllegalStateException.class, () -> confirmar(dados, estorno.getId()));
        assertTrue(erro.getMessage().contains("estorno"));
        assertEquals(StatusLancamento.RASCUNHO, estorno.getStatus());
        assertEquals(StatusLancamento.CONFIRMADO, original.getStatus());
        assertEquals(1L, ultimoNumero(dados));
    }

    @Test
    void confirmaTotaisAcimaDaPrecisaoIndividual() {
        Cenario dados = criarCenario();
        BigDecimal maximo = new BigDecimal("99999999999999999.99");
        UUID id = criarBalanceado(dados, maximo);
        adicionar(dados, id, TipoPartida.DEBITO, BigDecimal.ONE);
        adicionar(dados, id, TipoPartida.CREDITO, new BigDecimal("1.00"));

        assertEquals(StatusLancamento.CONFIRMADO, confirmar(dados, id).getStatus());
        assertEquals(1L, ultimoNumero(dados));
    }

    @Test
    void permiteUltimoBigintERejeitaProximaAlocacao() {
        Cenario dados = criarCenario();
        UUID ultimo = criarBalanceado(dados, VALOR);
        UUID excedente = criarBalanceado(dados, VALOR);
        entityManager.flush();
        jdbc.update("INSERT INTO sequencia_lancamento VALUES (?, ?, ?)",
                dados.empresaId(), dados.exercicioId(), Long.MAX_VALUE - 1);

        assertEquals(Long.MAX_VALUE, confirmar(dados, ultimo).getNumero());
        assertThrows(IllegalStateException.class, () -> confirmar(dados, excedente));
        assertEquals(Long.MAX_VALUE, ultimoNumero(dados));
        assertEquals(StatusLancamento.RASCUNHO, lancamentos.findById(excedente).orElseThrow().getStatus());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void reverteNumeroELancamentoQuandoBancoRejeitaAConfirmacaoAposAlocacao() {
        comSchemaDescartavel(schema -> {
            Cenario dados = transacaoNoSchema(schema, this::criarCenario);
            UUID id = transacaoNoSchema(schema, () -> {
                confirmar(dados, criarBalanceado(dados, VALOR));
                UUID outro = criarBalanceado(dados, VALOR);
                // Simula um contador legado defasado para provocar colisão no UPDATE da confirmação.
                jdbc.update("UPDATE sequencia_lancamento SET ultimo_numero = 0 WHERE empresa_id = ? AND exercicio_id = ?",
                        dados.empresaId(), dados.exercicioId());
                return outro;
            });

            DataIntegrityViolationException erro = assertThrows(DataIntegrityViolationException.class,
                    () -> transacaoNoSchema(schema, () -> confirmar(dados, id)));
            assertTrue(erro.getMostSpecificCause().getMessage().contains("uq_lancamento_numero_empresa_exercicio"));

            transacaoNoSchema(schema, () -> {
                LancamentoContabil rascunho = lancamentos.findById(id).orElseThrow();
                assertEquals(StatusLancamento.RASCUNHO, rascunho.getStatus());
                assertNull(rascunho.getNumero());
                assertNull(rascunho.getConfirmadoPor());
                assertNull(rascunho.getConfirmadoEm());
                assertEquals(0L, ultimoNumero(dados));
                return null;
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void confirmaConcorrentementeLancamentosDistintosNoMesmoExercicio() {
        comSchemaDescartavel(schema -> {
            Cenario dados = transacaoNoSchema(schema, this::criarCenario);
            List<UUID> ids = transacaoNoSchema(schema,
                    () -> List.of(criarBalanceado(dados, VALOR), criarBalanceado(dados, VALOR)));

            ResultadoConcorrente resultado = confirmarEmParalelo(schema, dados, ids);
            assertEquals(Set.of(1L, 2L), new HashSet<>(resultado.numeros()));
            assertEquals(0, resultado.rejeitados());
            transacaoNoSchema(schema, () -> {
                List<LancamentoContabil> confirmados = lancamentos.findAllById(ids);
                assertEquals(2, confirmados.size());
                assertTrue(confirmados.stream().allMatch(lancamento -> lancamento.getStatus() == StatusLancamento.CONFIRMADO));
                assertEquals(Set.of(1L, 2L),
                        new HashSet<>(confirmados.stream().map(LancamentoContabil::getNumero).toList()));
                assertEquals(2L, ultimoNumero(dados));
                return null;
            });
        });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void apenasUmaTentativaConcorrenteConfirmaOMesmoLancamento() {
        comSchemaDescartavel(schema -> {
            Cenario dados = transacaoNoSchema(schema, this::criarCenario);
            UUID id = transacaoNoSchema(schema, () -> criarBalanceado(dados, VALOR));

            ResultadoConcorrente resultado = confirmarEmParalelo(schema, dados, List.of(id, id));
            assertEquals(List.of(1L), resultado.numeros());
            assertEquals(1, resultado.rejeitados());
            transacaoNoSchema(schema, () -> {
                LancamentoContabil confirmado = lancamentos.findById(id).orElseThrow();
                assertEquals(StatusLancamento.CONFIRMADO, confirmado.getStatus());
                assertEquals(1L, confirmado.getNumero());
                assertEquals(1L, ultimoNumero(dados));
                return null;
            });
        });
    }

    private ResultadoConcorrente confirmarEmParalelo(String schema, Cenario dados, List<UUID> ids) {
        ExecutorService executor = Executors.newFixedThreadPool(ids.size());
        CountDownLatch prontas = new CountDownLatch(ids.size());
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Long>> tarefas = new ArrayList<>();
            for (UUID id : ids) {
                tarefas.add(executor.submit(() -> transacaoNoSchema(schema, () -> {
                    prontas.countDown();
                    aguardar(largada);
                    return confirmar(dados, id).getNumero();
                })));
            }
            aguardar(prontas);
            largada.countDown();
            List<Long> numeros = new ArrayList<>();
            int rejeitados = 0;
            for (Future<Long> tarefa : tarefas) {
                try {
                    numeros.add(tarefa.get(15, TimeUnit.SECONDS));
                } catch (ExecutionException erro) {
                    IllegalStateException rejeicao = assertInstanceOf(IllegalStateException.class, erro.getCause());
                    assertEquals("O lançamento deve estar em rascunho.", rejeicao.getMessage());
                    rejeitados++;
                }
            }
            return new ResultadoConcorrente(numeros, rejeitados);
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Teste concorrente interrompido.", erro);
        } catch (TimeoutException erro) {
            throw new AssertionError("Confirmações não terminaram dentro do prazo.", erro);
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

    private static void aguardar(CountDownLatch barreira) {
        try {
            assertTrue(barreira.await(10, TimeUnit.SECONDS), "As threads não se encontraram na barreira.");
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Thread de confirmação interrompida.", erro);
        }
    }

    private void comSchemaDescartavel(Consumer<String> teste) {
        assertEquals("lex_test|lex_test_runner", jdbc.queryForObject(
                "SELECT current_database() || '|' || current_user", String.class));
        // Cada teste que faz commit tem seu próprio schema, inclusive para preservar os triggers na limpeza.
        String schema = "lex_confirmacao_" + UUID.randomUUID().toString().replace("-", "");
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
        Empresa empresa = empresas.save(new Empresa(usuario, "Empresa teste", "Empresa teste", null));
        PlanoContas plano = planos.save(new PlanoContas(empresa, modelos.findById(1).orElseThrow()));
        ContaContabil conta = contas.save(new ContaContabil(plano,
                modelosContas.findAllByModeloVersaoOrderByCodigoAsc(1).stream()
                        .filter(modelo -> modelo.getTipo() == TipoConta.ANALITICA && !modelo.getContaRedutora())
                        .findFirst().orElseThrow(), null, null));
        return criarExercicio(usuario.getId(), empresa.getId(), conta.getId(), 2026);
    }

    private Cenario criarExercicio(UUID usuario, UUID empresa, UUID conta, int ano) {
        ExercicioContabil exercicio = exercicios.save(new ExercicioContabil(empresas.getReferenceById(empresa),
                (short) ano, LocalDate.of(ano, 1, 1), LocalDate.of(ano, 12, 31)));
        PeriodoContabil periodo = periodos.save(new PeriodoContabil(exercicio,
                (short) 1, LocalDate.of(ano, 1, 1), LocalDate.of(ano, 1, 31)));
        return new Cenario(usuario, empresa, exercicio.getId(), periodo.getId(), conta, LocalDate.of(ano, 1, 15));
    }

    private UUID criarRascunho(Cenario dados) {
        return service.criarRascunho(dados.empresaId(), dados.usuarioId(), dados.exercicioId(),
                dados.periodoId(), dados.data(), "Lançamento de teste").getId();
    }

    private UUID criarBalanceado(Cenario dados, BigDecimal valor) {
        UUID id = criarRascunho(dados);
        adicionar(dados, id, TipoPartida.DEBITO, valor);
        adicionar(dados, id, TipoPartida.CREDITO, valor);
        return id;
    }

    private void adicionar(Cenario dados, UUID id, TipoPartida tipo, BigDecimal valor) {
        service.adicionarPartida(dados.empresaId(), dados.usuarioId(), id, dados.contaId(), tipo, valor, null);
    }

    private LancamentoContabil confirmar(Cenario dados, UUID id) {
        return service.confirmarLancamento(dados.empresaId(), dados.usuarioId(), id);
    }

    private long ultimoNumero(Cenario dados) {
        return jdbc.queryForObject("""
                SELECT ultimo_numero FROM sequencia_lancamento WHERE empresa_id = ? AND exercicio_id = ?
                """, Long.class, dados.empresaId(), dados.exercicioId());
    }

    private void verificarRascunhoSemSequencia(Cenario dados, UUID id) {
        assertEquals(StatusLancamento.RASCUNHO, lancamentos.findById(id).orElseThrow().getStatus());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM sequencia_lancamento WHERE empresa_id = ? AND exercicio_id = ?",
                Long.class, dados.empresaId(), dados.exercicioId()));
    }

    private void alterarEstado(String sql, UUID id) {
        entityManager.flush();
        jdbc.update(sql, id);
        entityManager.clear();
    }

    private record Cenario(UUID usuarioId, UUID empresaId, UUID exercicioId, UUID periodoId, UUID contaId,
                           LocalDate data) {
    }

    private record ResultadoConcorrente(List<Long> numeros, int rejeitados) {
    }
}
