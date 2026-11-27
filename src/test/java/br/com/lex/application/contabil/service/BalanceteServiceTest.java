package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.AberturaContabil;
import br.com.lex.application.contabil.dto.AberturaContabil.SaldoPorConta;
import br.com.lex.application.contabil.dto.Balancete;
import br.com.lex.application.contabil.dto.LivroDiario;
import br.com.lex.domain.contabil.ApuracaoSaldos.NaturezaSaldo;
import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ExercicioContabil;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.PeriodoContabil;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.ClassificacaoBP;
import br.com.lex.domain.contabil.enums.NaturezaConta;
import br.com.lex.domain.contabil.enums.TipoPartida;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
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
class BalanceteServiceTest {

    private static final LocalDate DATA = LocalDate.of(2026, 1, 15);

    @Autowired private BalanceteService balancete;
    @Autowired private LancamentoContabilService lancamentos;
    @Autowired private LivroDiarioService diario;
    @Autowired private LivroRazaoService razao;
    @Autowired private ModeloContaContabilRepository modelos;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private Cenario dados;

    @BeforeEach
    void preparar() {
        dados = criarCenario();
    }

    @Test
    void reconciliaAberturaRecorteParcialDiarioERazaoSemDuplicarHierarquia() {
        lancamentos.registrarSaldosIniciais(new AberturaContabil(dados.empresa(), dados.usuario(),
                dados.exercicio(), dados.periodo(), DATA.minusDays(1), "Saldos de abertura",
                List.of(new SaldoPorConta(dados.debito(), TipoPartida.DEBITO, new BigDecimal("100.00")),
                        new SaldoPorConta(dados.credito(), TipoPartida.CREDITO, new BigDecimal("100.00")))));
        confirmar(dados, DATA, "20.00");
        confirmar(dados, DATA.plusDays(1), "9.00");
        criarComPartidas(dados, DATA, "77.00");

        Balancete relatorio = emitir(DATA, DATA);
        assertThat(relatorio.empresa().id()).isEqualTo(dados.empresa());
        assertThat(relatorio.empresa().razaoSocial()).isEqualTo("Empresa de teste");
        assertThat(relatorio.exercicio().id()).isEqualTo(dados.exercicio());
        assertThat(relatorio.exercicio().ano()).isEqualTo((short) 2026);
        assertThat(relatorio.inicio()).isEqualTo(DATA);
        assertThat(relatorio.fim()).isEqualTo(DATA);
        assertThat(relatorio.linhas()).extracting(l -> l.conta().codigo())
                .containsExactly("1", "1.1", "1.1.01", "1.1.01.001", "1.1.01.002", "1.1.01.003");
        Map<UUID, Balancete.Linha> linhas = porConta(relatorio);
        conferirSaldos(linhas.get(dados.debito()), "100.00", "20.00", "0.00", "120.00");
        conferirSaldos(linhas.get(dados.credito()), "-100.00", "0.00", "20.00", "-120.00");
        conferirSaldos(linhas.get(dados.semMovimento()), "0.00", "0.00", "0.00", "0.00");
        conferirSaldos(linhas.get(dados.disponibilidades()), "100.00", "20.00", "0.00", "120.00");
        conferirSaldos(linhas.get(dados.circulante()), "100.00", "20.00", "0.00", "120.00");
        conferirSaldos(linhas.get(dados.raiz()), "0.00", "20.00", "20.00", "0.00");
        assertThat(linhas.get(dados.credito()).conta().contaPaiId()).isEqualTo(dados.raiz());
        assertThat(linhas.get(dados.credito()).conta().natureza()).isEqualTo(NaturezaConta.DEVEDORA);
        assertThat(linhas.get(dados.credito()).saldos().saldoFinal().natureza()).isEqualTo(NaturezaSaldo.CREDOR);
        assertThat(linhas.get(dados.raiz()).saldos().saldoFinal().natureza()).isEqualTo(NaturezaSaldo.ZERADO);
        assertThat(linhas.get(dados.debito()).conta().classificacaoBP()).isEqualTo(ClassificacaoBP.ATIVO_CIRCULANTE);

        Balancete.Totais totais = relatorio.totais();
        assertThat(totais.saldoAnteriorDevedor()).isEqualByComparingTo("100.00");
        assertThat(totais.saldoAnteriorCredor()).isEqualByComparingTo("100.00");
        assertThat(totais.saldoFinalDevedor()).isEqualByComparingTo("120.00");
        assertThat(totais.saldoFinalCredor()).isEqualByComparingTo("120.00");
        assertThat(relatorio.equilibrio()).isEqualTo(new Balancete.Equilibrio(true, true, true));
        LivroDiario livro = diario.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), DATA, DATA);
        assertThat(totais.debitos()).isEqualByComparingTo(livro.totalDebitos()).isEqualByComparingTo("20.00");
        assertThat(totais.creditos()).isEqualByComparingTo(livro.totalCreditos()).isEqualByComparingTo("20.00");
        for (UUID conta : List.of(dados.debito(), dados.credito(), dados.semMovimento())) {
            assertThat(linhas.get(conta).saldos()).isEqualTo(razao.emitir(dados.empresa(), dados.usuario(),
                    dados.exercicio(), conta, DATA, DATA).saldos());
        }
    }

    @Test
    void preservaOriginalEstornoEContaInativaEmExercicioEncerrado() {
        UUID original = confirmar(dados, DATA, "40.00");
        LocalDate dataEstorno = DATA.plusMonths(1);
        UUID fevereiro = criarPeriodo(em.find(ExercicioContabil.class, dados.exercicio()), YearMonth.from(dataEstorno));
        em.flush();
        UUID estorno = lancamentos.criarRascunhoEstorno(dados.empresa(), dados.usuario(), original,
                dados.exercicio(), fevereiro, dataEstorno, "Estorno posterior").getId();
        lancamentos.confirmarEstorno(dados.empresa(), dados.usuario(), estorno);
        jdbc.update("update conta_contabil set ativa = false where id = ?", dados.debito());
        jdbc.update("update periodo_contabil set status = 'ENCERRADO' where exercicio_id = ?", dados.exercicio());
        jdbc.update("update exercicio_contabil set status = 'ENCERRADO' where id = ?", dados.exercicio());
        em.clear();

        Balancete.Linha janeiro = porConta(emitir(DATA, DATA)).get(dados.debito());
        assertThat(janeiro.conta().ativa()).isFalse();
        conferirSaldos(janeiro, "0.00", "40.00", "0.00", "40.00");

        Balancete fevereiroApurado = emitir(dataEstorno, dataEstorno);
        conferirSaldos(porConta(fevereiroApurado).get(dados.debito()), "40.00", "0.00", "40.00", "0.00");
        assertThat(fevereiroApurado.equilibrio()).isEqualTo(new Balancete.Equilibrio(true, true, true));
    }

    @Test
    void retornaPlanoZeradoSemEscrituracaoEIsolaEmpresas() {
        Cenario outro = criarCenario();
        confirmar(outro, DATA, "324.00");

        Balancete vazio = emitir(DATA, DATA);
        assertThat(vazio.linhas()).hasSize(6)
                .allSatisfy(l -> conferirSaldos(l, "0.00", "0.00", "0.00", "0.00"));
        assertThat(vazio.linhas()).extracting(l -> l.conta().id()).doesNotContain(outro.raiz(), outro.debito());
        assertThat(vazio.equilibrio()).isEqualTo(new Balancete.Equilibrio(true, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> balancete.emitir(dados.empresa(), outro.usuario(), dados.exercicio(), DATA, DATA));
        assertThrows(IllegalArgumentException.class,
                () -> balancete.emitir(dados.empresa(), dados.usuario(), outro.exercicio(), DATA, DATA));
        assertThrows(IllegalArgumentException.class, () -> emitir(DATA, DATA.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> emitir(DATA.minusYears(1), DATA));
        assertThrows(IllegalArgumentException.class, () -> emitir(DATA, DATA.plusYears(1)));
    }

    @Test
    void agregaAcimaDoLimiteMonetarioIndividual() {
        String limite = "99999999999999999.99";
        confirmar(dados, DATA, limite);
        confirmar(dados, DATA, limite);

        Balancete relatorio = emitir(DATA, DATA);
        String total = "199999999999999999.98";
        assertThat(relatorio.totais().debitos()).isEqualByComparingTo(total);
        assertThat(relatorio.totais().creditos()).isEqualByComparingTo(total);
        conferirSaldos(porConta(relatorio).get(dados.circulante()), "0.00", total, "0.00", total);
    }

    @Test
    void rejeitaHierarquiaCiclicaEmVezDeSomarParcialmente() {
        jdbc.update("update conta_contabil set conta_pai_id = ? where id = ?", dados.circulante(), dados.raiz());

        assertThat(assertThrows(IllegalStateException.class, () -> emitir(DATA, DATA)))
                .hasMessageContaining("ciclo");
    }

    private Balancete emitir(LocalDate inicio, LocalDate fim) {
        return balancete.emitir(dados.empresa(), dados.usuario(), dados.exercicio(), inicio, fim);
    }

    private Map<UUID, Balancete.Linha> porConta(Balancete balanceteEmitido) {
        return balanceteEmitido.linhas().stream().collect(toMap(l -> l.conta().id(), l -> l));
    }

    private void conferirSaldos(Balancete.Linha linha, String anterior, String debitos, String creditos, String saldoFinal) {
        assertThat(linha.saldos().saldoAnterior().valor()).isEqualByComparingTo(anterior);
        assertThat(linha.saldos().totalDebitos()).isEqualByComparingTo(debitos);
        assertThat(linha.saldos().totalCreditos()).isEqualByComparingTo(creditos);
        assertThat(linha.saldos().saldoFinal().valor()).isEqualByComparingTo(saldoFinal);
    }

    private UUID confirmar(Cenario cenario, LocalDate data, String valor) {
        UUID id = criarComPartidas(cenario, data, valor);
        return lancamentos.confirmarLancamento(cenario.empresa(), cenario.usuario(), id).getId();
    }

    private UUID criarComPartidas(Cenario cenario, LocalDate data, String valor) {
        UUID id = lancamentos.criarRascunho(cenario.empresa(), cenario.usuario(), cenario.exercicio(),
                cenario.periodo(), data, "Movimento manual").getId();
        lancamentos.adicionarPartida(cenario.empresa(), cenario.usuario(), id, cenario.debito(),
                TipoPartida.DEBITO, new BigDecimal(valor), null);
        lancamentos.adicionarPartida(cenario.empresa(), cenario.usuario(), id, cenario.credito(),
                TipoPartida.CREDITO, new BigDecimal(valor), null);
        return id;
    }

    private Cenario criarCenario() {
        Usuario usuario = new Usuario("Titular", UUID.randomUUID() + "@example.test", "hash-teste");
        em.persist(usuario);
        Empresa empresa = new Empresa(usuario, "Empresa de teste", "Empresa de teste", null);
        em.persist(empresa);
        ExercicioContabil exercicio = new ExercicioContabil(empresa, (short) DATA.getYear(),
                DATA.withDayOfYear(1), LocalDate.of(DATA.getYear(), 12, 31));
        em.persist(exercicio);
        UUID periodo = criarPeriodo(exercicio, YearMonth.from(DATA));

        Map<String, ModeloContaContabil> modelo = modelos.findAllByModeloVersaoOrderByCodigoAsc(1)
                .stream().collect(toMap(ModeloContaContabil::getCodigo, m -> m));
        PlanoContas plano = new PlanoContas(empresa, modelo.get("1").getModeloPlanoContas());
        em.persist(plano);
        UUID raiz = criarConta(plano, modelo.get("1"), null);
        UUID circulante = criarConta(plano, modelo.get("1.1"), raiz);
        UUID disponibilidades = criarConta(plano, modelo.get("1.1.01"), circulante);
        UUID debito = criarConta(plano, modelo.get("1.1.01.001"), disponibilidades);
        // O prefixo do código aponta para disponibilidades, mas o vínculo persistido aponta para a raiz.
        UUID credito = criarConta(plano, modelo.get("1.1.01.002"), raiz);
        UUID semMovimento = criarConta(plano, modelo.get("1.1.01.003"), disponibilidades);
        em.flush();
        return new Cenario(empresa.getId(), usuario.getId(), exercicio.getId(), periodo,
                raiz, circulante, disponibilidades, debito, credito, semMovimento);
    }

    private UUID criarConta(PlanoContas plano, ModeloContaContabil modelo, UUID paiId) {
        ContaContabil conta = new ContaContabil(plano, modelo, paiId, null);
        em.persist(conta);
        return conta.getId();
    }

    private UUID criarPeriodo(ExercicioContabil exercicio, YearMonth mes) {
        PeriodoContabil periodo = new PeriodoContabil(exercicio, (short) mes.getMonthValue(),
                mes.atDay(1), mes.atEndOfMonth());
        em.persist(periodo);
        return periodo.getId();
    }

    private record Cenario(UUID empresa, UUID usuario, UUID exercicio, UUID periodo, UUID raiz, UUID circulante,
                           UUID disponibilidades, UUID debito, UUID credito, UUID semMovimento) {
    }
}
