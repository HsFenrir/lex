package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.*;
import br.com.lex.domain.contabil.enums.OrigemLancamento;
import br.com.lex.domain.contabil.enums.TipoPartida;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PartidaContabilRepositoryTest {

    @Autowired
    private PartidaContabilRepository partidaRepository;

    @Autowired
    private LancamentoContabilRepository lancamentoRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ModeloPlanoContasRepository modeloPlanoContasRepository;

    @Autowired
    private ModeloContaContabilRepository modeloContaRepository;

    @Autowired
    private PlanoContasRepository planoContasRepository;

    @Autowired
    private ContaContabilRepository contaContabilRepository;

    @Autowired
    private ExercicioContabilRepository exercicioRepository;

    @Autowired
    private PeriodoContabilRepository periodoRepository;

    private Usuario usuario;
    private Empresa empresa;
    private PlanoContas planoContas;
    private ExercicioContabil exercicio;
    private PeriodoContabil periodo;
    private LancamentoContabil lancamento;
    private ContaContabil conta;

    @BeforeEach
    void configurarCenario() {
        usuario = usuarioRepository.save(
                new Usuario(
                        "Usuário Teste",
                        "partida.repository@lex.com",
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

        lancamento = lancamentoRepository.save(
                new LancamentoContabil(
                        empresa,
                        exercicio,
                        periodo,
                        planoContas,
                        usuario,
                        LocalDate.of(2026, 1, 15),
                        "Lançamento para teste de partidas",
                        OrigemLancamento.MANUAL
                )
        );

        ModeloContaContabil modeloConta = modeloContaRepository
                .findAll()
                .stream()
                .filter(c -> c.getTipo() != null
                        && c.getTipo().name().equals("ANALITICA"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "O modelo padrão não possui conta analítica."
                ));

        conta = contaContabilRepository.save(
                new ContaContabil(
                        planoContas,
                        modeloConta,
                        null,
                        null
                )
        );
    }

    @Test
    void devePersistirEBuscarPartidasPorLancamento() {
        PartidaContabil debito = partidaRepository.save(
                new PartidaContabil(
                        lancamento,
                        conta,
                        TipoPartida.DEBITO,
                        new BigDecimal("150.00"),
                        "Partida de débito"
                )
        );

        PartidaContabil credito = partidaRepository.save(
                new PartidaContabil(
                        lancamento,
                        conta,
                        TipoPartida.CREDITO,
                        new BigDecimal("150.00"),
                        "Partida de crédito"
                )
        );

        List<PartidaContabil> resultado =
                partidaRepository.findAllByLancamentoIdOrderByIdAsc(
                        lancamento.getId()
                );

        assertEquals(2, resultado.size());
        assertTrue(resultado.stream().anyMatch(
                p -> p.getId().equals(debito.getId())
        ));
        assertTrue(resultado.stream().anyMatch(
                p -> p.getId().equals(credito.getId())
        ));
    }

    @Test
    void deveBuscarPartidasPorLancamentoEPlanoDeContas() {
        partidaRepository.save(
                new PartidaContabil(
                        lancamento,
                        conta,
                        TipoPartida.DEBITO,
                        new BigDecimal("100.00"),
                        "Débito"
                )
        );

        List<PartidaContabil> resultado =
                partidaRepository
                        .findAllByLancamentoIdAndPlanoContasIdOrderByIdAsc(
                                lancamento.getId(),
                                planoContas.getId()
                        );

        assertEquals(1, resultado.size());
        assertEquals(lancamento.getId(), resultado.get(0).getLancamentoId());
        assertEquals(planoContas.getId(), resultado.get(0).getPlanoContasId());
    }

    @Test
    void deveVerificarExistenciaDePartidasPorLancamento() {
        assertFalse(
                partidaRepository.existsByLancamentoId(lancamento.getId())
        );

        partidaRepository.save(
                new PartidaContabil(
                        lancamento,
                        conta,
                        TipoPartida.DEBITO,
                        new BigDecimal("80.00"),
                        "Débito"
                )
        );

        assertTrue(
                partidaRepository.existsByLancamentoId(lancamento.getId())
        );
    }

    @Test
    void deveContarPartidasPorLancamento() {
        assertEquals(
                0,
                partidaRepository.countByLancamentoId(lancamento.getId())
        );

        partidaRepository.save(
                new PartidaContabil(
                        lancamento,
                        conta,
                        TipoPartida.DEBITO,
                        new BigDecimal("200.00"),
                        "Débito"
                )
        );

        partidaRepository.save(
                new PartidaContabil(
                        lancamento,
                        conta,
                        TipoPartida.CREDITO,
                        new BigDecimal("200.00"),
                        "Crédito"
                )
        );

        assertEquals(
                2,
                partidaRepository.countByLancamentoId(lancamento.getId())
        );
    }

    @Test
    void deveRetornarListaVaziaParaOutroPlanoDeContas() {
        assertTrue(
                partidaRepository
                        .findAllByLancamentoIdAndPlanoContasIdOrderByIdAsc(
                                lancamento.getId(),
                                UUID.randomUUID()
                        )
                        .isEmpty()
        );
    }
}