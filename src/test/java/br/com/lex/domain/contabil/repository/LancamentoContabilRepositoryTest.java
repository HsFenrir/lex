package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.*;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.StatusLancamento;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LancamentoContabilRepositoryTest {

    @Autowired
    private LancamentoContabilRepository lancamentoRepository;

    @Autowired
    private PartidaContabilRepository partidaRepository;

    @Autowired
    private ContaContabilRepository contaContabilRepository;

    @Autowired
    private ModeloContaContabilRepository modeloContaContabilRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ModeloPlanoContasRepository modeloPlanoContasRepository;

    @Autowired
    private PlanoContasRepository planoContasRepository;

    @Autowired
    private ExercicioContabilRepository exercicioRepository;

    @Autowired
    private PeriodoContabilRepository periodoRepository;

    private Usuario usuario;
    private Empresa empresa;
    private PlanoContas planoContas;
    private ExercicioContabil exercicio;
    private PeriodoContabil periodo;

    @BeforeEach
    void configurarCenario() {
        usuario = usuarioRepository.save(
                new Usuario(
                        "Usuário Teste",
                        "lancamento.repository@lex.com",
                        "hash-teste"
                )
        );

        empresa = empresaRepository.save(
                new Empresa(
                        usuario,
                        "Empresa Teste",
                        "Empresa Teste",
                        null
                )
        );

        ModeloPlanoContas modelo = modeloPlanoContasRepository
                .findById(1)
                .orElseThrow();

        planoContas = planoContasRepository.save(
                new PlanoContas(empresa, modelo)
        );

        exercicio = exercicioRepository.save(
                new ExercicioContabil(
                        empresa,
                        (short) 2026,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31)
                )
        );

        periodo = periodoRepository.save(
                new PeriodoContabil(
                        exercicio,
                        (short) 1,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 1, 31)
                )
        );
    }

    private LancamentoContabil criarLancamento(LocalDate data) {
        return lancamentoRepository.save(
                new LancamentoContabil(
                        empresa,
                        exercicio,
                        periodo,
                        planoContas,
                        usuario,
                        data,
                        "Lançamento de teste",
                        OrigemLancamento.MANUAL
                )
        );
    }

    @Test
    void deveBuscarLancamentoPorIdEEmpresa() {
        LancamentoContabil lancamento =
                criarLancamento(LocalDate.of(2026, 1, 10));

        assertTrue(
                lancamentoRepository.findByIdAndEmpresaId(
                        lancamento.getId(),
                        empresa.getId()
                ).isPresent()
        );

        assertFalse(
                lancamentoRepository.findByIdAndEmpresaId(
                        lancamento.getId(),
                        java.util.UUID.randomUUID()
                ).isPresent()
        );
    }

    @Test
    void deveListarLancamentosPorEmpresaEExercicioOrdenadosPorData() {
        LancamentoContabil segundo = criarLancamento(
                LocalDate.of(2026, 1, 20)
        );

        LancamentoContabil primeiro = criarLancamento(
                LocalDate.of(2026, 1, 5)
        );

        List<LancamentoContabil> resultado =
                lancamentoRepository
                        .findAllByEmpresaIdAndExercicioIdOrderByDataAscNumeroAsc(
                                empresa.getId(),
                                exercicio.getId()
                        );

        assertEquals(2, resultado.size());
        assertEquals(primeiro.getId(), resultado.get(0).getId());
        assertEquals(segundo.getId(), resultado.get(1).getId());
    }

    @Test
    void deveListarLancamentosPorEmpresaExercicioEPeriodo() {
        LancamentoContabil lancamento =
                criarLancamento(LocalDate.of(2026, 1, 15));

        List<LancamentoContabil> resultado =
                lancamentoRepository
                        .findAllByEmpresaIdAndExercicioIdAndPeriodoIdOrderByDataAscNumeroAsc(
                                empresa.getId(),
                                exercicio.getId(),
                                periodo.getId()
                        );

        assertEquals(1, resultado.size());
        assertEquals(lancamento.getId(), resultado.get(0).getId());
    }

    @Test
    void deveBuscarLancamentoPorIdEmpresaEStatus() {
        LancamentoContabil lancamento =
                criarLancamento(LocalDate.of(2026, 1, 15));

        assertTrue(
                lancamentoRepository.findByIdAndEmpresaIdAndStatus(
                        lancamento.getId(),
                        empresa.getId(),
                        StatusLancamento.RASCUNHO
                ).isPresent()
        );

        assertFalse(
                lancamentoRepository.findByIdAndEmpresaIdAndStatus(
                        lancamento.getId(),
                        empresa.getId(),
                        StatusLancamento.CONFIRMADO
                ).isPresent()
        );
    }

    @Test
    void naoDeveEncontrarEstornoQuandoNaoExistir() {
        LancamentoContabil original =
                criarLancamento(LocalDate.of(2026, 1, 15));

        assertTrue(
                lancamentoRepository.findByEstornoDeIdAndEmpresaId(
                        original.getId(),
                        empresa.getId()
                ).isEmpty()
        );
    }

    @Test
    void deveEncontrarRascunhoDeEstornoEReservarLancamentoOriginal() {
        LancamentoContabil original =
                criarLancamento(LocalDate.of(2026, 1, 15));
        lancamentoRepository.flush();

        List<ContaContabil> contas = modeloContaContabilRepository
                .findAllByModeloVersaoOrderByCodigoAsc(planoContas.getModeloVersao())
                .stream()
                .filter(conta -> conta.getTipo() == TipoConta.ANALITICA)
                .filter(conta -> Boolean.FALSE.equals(conta.getContaRedutora()))
                .limit(2)
                .map(conta -> new ContaContabil(planoContas, conta, null, null))
                .toList();
        assertEquals(2, contas.size());
        contaContabilRepository.saveAllAndFlush(contas);

        BigDecimal valor = new BigDecimal("100.00");
        partidaRepository.saveAllAndFlush(List.of(
                new PartidaContabil(original, contas.get(0), TipoPartida.DEBITO, valor, null),
                new PartidaContabil(original, contas.get(1), TipoPartida.CREDITO, valor, null)
        ));

        // O exercício da fixture é novo, portanto esta é sua primeira confirmação.
        long numero = 1L;
        entityManager.createNativeQuery("""
                INSERT INTO sequencia_lancamento (empresa_id, exercicio_id, ultimo_numero)
                VALUES (:empresaId, :exercicioId, :numero)
                """)
                .setParameter("empresaId", empresa.getId())
                .setParameter("exercicioId", exercicio.getId())
                .setParameter("numero", numero)
                .executeUpdate();
        original.confirmar(numero, usuario, OffsetDateTime.now());
        lancamentoRepository.flush();

        LocalDate dataEstorno = LocalDate.of(2026, 2, 1);
        PeriodoContabil periodoDestino = periodoRepository.saveAndFlush(
                new PeriodoContabil(exercicio, (short) 2, dataEstorno, dataEstorno.plusDays(27))
        );
        LancamentoContabil estorno = lancamentoRepository.saveAndFlush(
                LancamentoContabil.criarEstorno(
                        original,
                        exercicio,
                        periodoDestino,
                        usuario,
                        dataEstorno,
                        "Estorno de teste"
                )
        );
        entityManager.clear();

        LancamentoContabil resultado =
                lancamentoRepository.findByEstornoDeIdAndEmpresaId(
                        original.getId(),
                        empresa.getId()
                ).orElseThrow();

        assertEquals(estorno.getId(), resultado.getId());
        assertEquals(original.getId(), resultado.getEstornoDeId());
        assertEquals(exercicio.getId(), resultado.getExercicioId());
        assertEquals(periodoDestino.getId(), resultado.getPeriodoId());
        assertEquals(StatusLancamento.RASCUNHO, resultado.getStatus());
        assertNull(resultado.getNumero());

        LancamentoContabil originalConfirmado = lancamentoRepository
                .findByIdAndEmpresaIdAndStatus(original.getId(), empresa.getId(), StatusLancamento.CONFIRMADO)
                .orElseThrow();
        LancamentoContabil outroEstorno = LancamentoContabil.criarEstorno(
                originalConfirmado, exercicio, periodoDestino, usuario, dataEstorno, "Segundo estorno"
        );
        DataIntegrityViolationException falha = assertThrows(
                DataIntegrityViolationException.class,
                () -> lancamentoRepository.saveAndFlush(outroEstorno)
        );
        assertTrue(falha.getMostSpecificCause().getMessage().contains("uq_lancamento_estorno_unico"));
    }
}
