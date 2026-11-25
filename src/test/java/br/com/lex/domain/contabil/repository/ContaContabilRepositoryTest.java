package br.com.lex.domain.contabil.repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.ModeloPlanoContas;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.TipoConta;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class ContaContabilRepositoryTest {

    @Autowired
    private ContaContabilRepository contaContabilRepository;

    @Autowired
    private PlanoContasRepository planoContasRepository;

    @Autowired
    private ModeloPlanoContasRepository modeloPlanoContasRepository;

    @Autowired
    private ModeloContaContabilRepository modeloContaContabilRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void devePersistirContaSinteticaEContaAnaliticaComHierarquia() {

        Usuario usuario = new Usuario(
                "Usuário de Teste",
                "conta-" + UUID.randomUUID() + "@teste.com",
                "hash-senha-teste"
        );

        entityManager.persist(usuario);
        entityManager.flush();

        Empresa empresa = new Empresa(
                usuario,
                "Empresa de Teste Ltda.",
                "Empresa Teste",
                "11222333000181"
        );

        entityManager.persist(empresa);
        entityManager.flush();

        ModeloPlanoContas modelo = modeloPlanoContasRepository
                .findById(1)
                .orElseThrow(() -> new AssertionError(
                        "Modelo padrão versão 1 não encontrado."
                ));

        PlanoContas plano = planoContasRepository.saveAndFlush(
                new PlanoContas(empresa, modelo)
        );

        List<ModeloContaContabil> contasModelo =
                modeloContaContabilRepository
                        .findAllByModeloVersaoOrderByCodigoAsc(1);

        ModeloContaContabil contaPaiModelo = contasModelo.stream()
                .filter(conta -> conta.getTipo() == TipoConta.SINTETICA)
                .filter(pai -> contasModelo.stream().anyMatch(filha ->
                        pai.getId().equals(filha.getContaPaiId())
                                && filha.getTipo() == TipoConta.ANALITICA
                ))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Não foi encontrada uma conta sintética "
                                + "com conta analítica subordinada no modelo."
                ));

        ModeloContaContabil contaFilhaModelo = contasModelo.stream()
                .filter(conta ->
                        contaPaiModelo.getId().equals(conta.getContaPaiId())
                )
                .filter(conta -> conta.getTipo() == TipoConta.ANALITICA)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Conta analítica subordinada não encontrada."
                ));

        ContaContabil contaPai = contaContabilRepository.saveAndFlush(
                new ContaContabil(
                        plano,
                        contaPaiModelo,
                        null,
                        null
                )
        );

        ContaContabil contaFilha = contaContabilRepository.saveAndFlush(
                new ContaContabil(
                        plano,
                        contaFilhaModelo,
                        contaPai.getId(),
                        null
                )
        );

        assertNotNull(contaPai.getId());
        assertNotNull(contaFilha.getId());

        assertEquals(contaPai.getId(), contaFilha.getContaPaiId());
        assertEquals(contaPaiModelo.getCodigo(), contaPai.getCodigo());
        assertEquals(contaFilhaModelo.getCodigo(), contaFilha.getCodigo());

        entityManager.clear();

        ContaContabil paiRecuperado = contaContabilRepository
                .findById(contaPai.getId())
                .orElseThrow();

        ContaContabil filhaRecuperada = contaContabilRepository
                .findById(contaFilha.getId())
                .orElseThrow();

        assertEquals(plano.getId(), paiRecuperado.getPlanoContasId());
        assertEquals(plano.getId(), filhaRecuperada.getPlanoContasId());

        assertEquals(
                contaPai.getId(),
                filhaRecuperada.getContaPai().getId()
        );

        assertEquals(
                TipoConta.SINTETICA,
                paiRecuperado.getTipo()
        );

        assertEquals(
                TipoConta.ANALITICA,
                filhaRecuperada.getTipo()
        );

        assertEquals(
                contaPaiModelo.getId(),
                paiRecuperado.getModeloContaId()
        );

        assertEquals(
                contaFilhaModelo.getId(),
                filhaRecuperada.getModeloContaId()
        );
    }
    @Test
    void devePersistirContaRedutoraERecuperarContaReduzida() {

        Usuario usuario = new Usuario(
                "Usuário de Teste",
                "redutora-" + UUID.randomUUID() + "@teste.com",
                "hash-senha-teste"
        );

        entityManager.persist(usuario);
        entityManager.flush();

        Empresa empresa = new Empresa(
                usuario,
                "Empresa de Teste Ltda.",
                "Empresa Teste",
                "11222333000181"
        );

        entityManager.persist(empresa);
        entityManager.flush();

        ModeloPlanoContas modelo = modeloPlanoContasRepository
                .findById(1)
                .orElseThrow(() -> new AssertionError(
                        "Modelo padrão versão 1 não encontrado."
                ));

        PlanoContas plano = planoContasRepository.saveAndFlush(
                new PlanoContas(empresa, modelo)
        );

        List<ModeloContaContabil> contasModelo =
                modeloContaContabilRepository
                        .findAllByModeloVersaoOrderByCodigoAsc(1);

        Map<UUID, ModeloContaContabil> modelosPorId = new HashMap<>();

        for (ModeloContaContabil conta : contasModelo) {
            modelosPorId.put(conta.getId(), conta);
        }

        ModeloContaContabil contaRedutoraModelo = contasModelo.stream()
                .filter(conta -> Boolean.TRUE.equals(conta.getContaRedutora()))
                .filter(conta -> conta.getContaReduzidaId() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Nenhuma conta redutora válida foi encontrada no modelo."
                ));

        ModeloContaContabil contaReduzidaModelo = modelosPorId.get(
                contaRedutoraModelo.getContaReduzidaId()
        );

        assertNotNull(
                contaReduzidaModelo,
                "A conta reduzida referenciada não existe no modelo."
        );

        Map<UUID, ContaContabil> contasPersistidas = new HashMap<>();
        Set<UUID> contasEmProcessamento = new HashSet<>();

        ContaContabil contaReduzida = persistirContaDoModelo(
                contaReduzidaModelo,
                plano,
                modelosPorId,
                contasPersistidas,
                contasEmProcessamento
        );

        ContaContabil contaRedutora = persistirContaDoModelo(
                contaRedutoraModelo,
                plano,
                modelosPorId,
                contasPersistidas,
                contasEmProcessamento
        );

        assertNotNull(contaRedutora.getId());
        assertNotNull(contaReduzida.getId());

        assertTrue(contaRedutora.getContaRedutora());

        assertEquals(
                contaReduzida.getId(),
                contaRedutora.getContaReduzidaId()
        );

        entityManager.clear();

        ContaContabil redutoraRecuperada = contaContabilRepository
                .findById(contaRedutora.getId())
                .orElseThrow();

        assertEquals(
                plano.getId(),
                redutoraRecuperada.getPlanoContasId()
        );

        assertTrue(redutoraRecuperada.getContaRedutora());

        assertEquals(
                contaReduzida.getId(),
                redutoraRecuperada.getContaReduzidaId()
        );

        assertNotNull(redutoraRecuperada.getContaReduzida());

        assertEquals(
                contaReduzida.getId(),
                redutoraRecuperada.getContaReduzida().getId()
        );

        assertEquals(
                contaReduzidaModelo.getId(),
                redutoraRecuperada.getContaReduzida().getModeloContaId()
        );

        assertEquals(
                contaRedutoraModelo.getId(),
                redutoraRecuperada.getModeloContaId()
        );
    }
    @Test
    void deveImpedirContaPaiPertencenteAOutroPlano() {

        PlanoContas planoOrigem = criarPlanoTeste(
                "pai-origem",
                "11222333000181"
        );

        PlanoContas planoDestino = criarPlanoTeste(
                "pai-destino",
                "11222333000182"
        );

        List<ModeloContaContabil> contasModelo =
                modeloContaContabilRepository
                        .findAllByModeloVersaoOrderByCodigoAsc(1);

        ModeloContaContabil contaPaiModelo = contasModelo.stream()
                .filter(conta -> conta.getTipo() == TipoConta.SINTETICA)
                .filter(pai -> contasModelo.stream().anyMatch(filha ->
                        pai.getId().equals(filha.getContaPaiId())
                                && filha.getTipo() == TipoConta.ANALITICA
                ))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Não foi encontrada uma conta sintética "
                                + "com conta analítica subordinada."
                ));

        ModeloContaContabil contaFilhaModelo = contasModelo.stream()
                .filter(conta ->
                        contaPaiModelo.getId().equals(conta.getContaPaiId())
                )
                .filter(conta -> conta.getTipo() == TipoConta.ANALITICA)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Conta analítica subordinada não encontrada."
                ));

        ContaContabil contaPaiOrigem = contaContabilRepository.saveAndFlush(
                new ContaContabil(
                        planoOrigem,
                        contaPaiModelo,
                        null,
                        null
                )
        );

        ContaContabil contaFilhaDestino = new ContaContabil(
                planoDestino,
                contaFilhaModelo,
                contaPaiOrigem.getId(),
                null
        );

        DataIntegrityViolationException excecao = assertThrows(
                DataIntegrityViolationException.class,
                () -> contaContabilRepository.saveAndFlush(contaFilhaDestino)
        );

        assertTrue(
                excecao.getMostSpecificCause()
                        .getMessage()
                        .contains("fk_conta_pai"),
                "A violação deveria ocorrer na constraint fk_conta_pai."
        );
    }

    @Test
    void deveImpedirContaReduzidaPertencenteAOutroPlano() {

        PlanoContas planoOrigem = criarPlanoTeste(
                "reduzida-origem",
                "11222333000183"
        );

        PlanoContas planoDestino = criarPlanoTeste(
                "reduzida-destino",
                "11222333000184"
        );

        List<ModeloContaContabil> contasModelo =
                modeloContaContabilRepository
                        .findAllByModeloVersaoOrderByCodigoAsc(1);

        Map<UUID, ModeloContaContabil> modelosPorId = new HashMap<>();

        for (ModeloContaContabil conta : contasModelo) {
            modelosPorId.put(conta.getId(), conta);
        }

        ModeloContaContabil contaRedutoraModelo = contasModelo.stream()
                .filter(conta -> Boolean.TRUE.equals(conta.getContaRedutora()))
                .filter(conta -> conta.getContaReduzidaId() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Nenhuma conta redutora válida foi encontrada no modelo."
                ));

        ModeloContaContabil contaReduzidaModelo = modelosPorId.get(
                contaRedutoraModelo.getContaReduzidaId()
        );

        assertNotNull(
                contaReduzidaModelo,
                "A conta reduzida referenciada não existe no modelo."
        );

        ContaContabil contaReduzidaOrigem = contaContabilRepository.saveAndFlush(
                new ContaContabil(
                        planoOrigem,
                        contaReduzidaModelo,
                        null,
                        null
                )
        );

        ContaContabil contaRedutoraDestino = new ContaContabil(
                planoDestino,
                contaRedutoraModelo,
                null,
                contaReduzidaOrigem.getId()
        );

        DataIntegrityViolationException excecao = assertThrows(
                DataIntegrityViolationException.class,
                () -> contaContabilRepository.saveAndFlush(contaRedutoraDestino)
        );

        assertTrue(
                excecao.getMostSpecificCause()
                        .getMessage()
                        .contains("fk_conta_reduzida"),
                "A violação deveria ocorrer na constraint fk_conta_reduzida."
        );
    }

    private PlanoContas criarPlanoTeste(String identificador, String cnpj) {

        Usuario usuario = new Usuario(
                "Usuário " + identificador,
                identificador + "-" + UUID.randomUUID() + "@teste.com",
                "hash-senha-teste"
        );

        entityManager.persist(usuario);
        entityManager.flush();

        Empresa empresa = new Empresa(
                usuario,
                "Empresa " + identificador + " Ltda.",
                "Empresa " + identificador,
                cnpj
        );

        entityManager.persist(empresa);
        entityManager.flush();

        ModeloPlanoContas modelo = modeloPlanoContasRepository
                .findById(1)
                .orElseThrow(() -> new AssertionError(
                        "Modelo padrão versão 1 não encontrado."
                ));

        return planoContasRepository.saveAndFlush(
                new PlanoContas(empresa, modelo)
        );
    }
    private ContaContabil persistirContaDoModelo(
            ModeloContaContabil modeloConta,
            PlanoContas plano,
            Map<UUID, ModeloContaContabil> modelosPorId,
            Map<UUID, ContaContabil> contasPersistidas,
            Set<UUID> contasEmProcessamento
    ) {

        ContaContabil contaExistente = contasPersistidas.get(
                modeloConta.getId()
        );

        if (contaExistente != null) {
            return contaExistente;
        }

        if (!contasEmProcessamento.add(modeloConta.getId())) {
            throw new AssertionError(
                    "Foi detectada uma dependência circular no modelo de contas."
            );
        }

        UUID contaPaiId = null;

        if (modeloConta.getContaPaiId() != null) {
            ModeloContaContabil paiModelo = modelosPorId.get(
                    modeloConta.getContaPaiId()
            );

            if (paiModelo == null) {
                throw new AssertionError(
                        "Conta-pai não encontrada no modelo."
                );
            }

            ContaContabil contaPai = persistirContaDoModelo(
                    paiModelo,
                    plano,
                    modelosPorId,
                    contasPersistidas,
                    contasEmProcessamento
            );

            contaPaiId = contaPai.getId();
        }

        UUID contaReduzidaId = null;

        if (modeloConta.getContaReduzidaId() != null) {
            ModeloContaContabil reduzidaModelo = modelosPorId.get(
                    modeloConta.getContaReduzidaId()
            );

            if (reduzidaModelo == null) {
                throw new AssertionError(
                        "Conta reduzida não encontrada no modelo."
                );
            }

            ContaContabil contaReduzida = persistirContaDoModelo(
                    reduzidaModelo,
                    plano,
                    modelosPorId,
                    contasPersistidas,
                    contasEmProcessamento
            );

            contaReduzidaId = contaReduzida.getId();
        }

        ContaContabil novaConta = contaContabilRepository.saveAndFlush(
                new ContaContabil(
                        plano,
                        modeloConta,
                        contaPaiId,
                        contaReduzidaId
                )
        );

        contasPersistidas.put(modeloConta.getId(), novaConta);
        contasEmProcessamento.remove(modeloConta.getId());

        return novaConta;
    }
}