
package br.com.lex.application.contabil.service;

import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.ModeloPlanoContas;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloPlanoContasRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PlanoContasServiceTest {

    @Autowired
    private PlanoContasService planoContasService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    @Autowired
    private ModeloPlanoContasRepository modeloPlanoContasRepository;

    @Autowired
    private ModeloContaContabilRepository modeloContaContabilRepository;

    @Autowired
    private PlanoContasRepository planoContasRepository;

    @Autowired
    private ContaContabilRepository contaContabilRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void deveProvisionarPlanoDeContasCopiandoIntegralmenteModeloAtivo() {

        // Arrange: cria usuário e empresa de teste.

        Usuario usuario = new Usuario(
                "Usuário de Teste",
                "teste-" + UUID.randomUUID() + "@lex.com",
                "hash-de-senha-de-teste"
        );

        usuario = usuarioRepository.saveAndFlush(usuario);

        String cnpj = "11222333000181";

        Empresa empresa = new Empresa(
                usuario,
                "Empresa de Teste LTDA",
                "Empresa Teste",
                cnpj
        );

        empresa = empresaRepository.saveAndFlush(empresa);

        // Obtém o modelo padrão ativo.

        List<ModeloPlanoContas> modelosAtivos =
                modeloPlanoContasRepository.findAllByAtivoTrue();

        assertEquals(
                1,
                modelosAtivos.size(),
                "Deve existir exatamente um modelo de plano de contas ativo."
        );

        ModeloPlanoContas modelo = modelosAtivos.get(0);

        List<ModeloContaContabil> contasModelo =
                modeloContaContabilRepository
                        .findAllByModeloVersaoOrderByCodigoAsc(
                                modelo.getVersao()
                        );

        assertFalse(
                contasModelo.isEmpty(),
                "O modelo padrão deve possuir contas cadastradas."
        );

        // Act: provisiona o plano de contas.

        PlanoContas plano = planoContasService.provisionar(
                empresa.getId(),
                usuario.getId()
        );

        entityManager.flush();
        entityManager.clear();

        // Assert: verifica o plano criado.

        assertNotNull(plano.getId());

        PlanoContas planoSalvo = planoContasRepository
                .findByEmpresaId(empresa.getId())
                .orElseThrow();

        assertEquals(plano.getId(), planoSalvo.getId());

        assertEquals(
                empresa.getId(),
                planoSalvo.getEmpresaId()
        );

        assertEquals(
                modelo.getVersao(),
                planoSalvo.getModeloVersao()
        );

        // Verifica a quantidade de contas copiadas.

        List<ContaContabil> contasCopiadas =
                contaContabilRepository
                        .findAllByPlanoContasIdOrderByCodigoAsc(
                                planoSalvo.getId()
                        );

        assertEquals(
                contasModelo.size(),
                contasCopiadas.size(),
                "Todas as contas do modelo devem ser copiadas."
        );

        // Cria um índice das contas copiadas pelo ID da conta original.

        Map<UUID, ContaContabil> contasCopiadasPorModeloId =
                new HashMap<>();

        for (ContaContabil conta : contasCopiadas) {

            assertNotNull(conta.getId());

            assertEquals(
                    planoSalvo.getId(),
                    conta.getPlanoContasId()
            );

            assertTrue(
                    conta.getAtiva(),
                    "As contas copiadas devem iniciar ativas."
            );

            assertNull(
                    contasCopiadasPorModeloId.put(
                            conta.getModeloContaId(),
                            conta
                    ),
                    "Não deve existir mais de uma cópia da mesma conta do modelo."
            );
        }

        // Confere os dados e relacionamentos de cada conta.

        for (ModeloContaContabil contaModelo : contasModelo) {

            ContaContabil copia = contasCopiadasPorModeloId.get(
                    contaModelo.getId()
            );

            assertNotNull(
                    copia,
                    "A conta " + contaModelo.getCodigo()
                            + " não foi copiada."
            );

            assertEquals(contaModelo.getCodigo(), copia.getCodigo());
            assertEquals(contaModelo.getNome(), copia.getNome());
            assertEquals(contaModelo.getGrupo(), copia.getGrupo());
            assertEquals(contaModelo.getNatureza(), copia.getNatureza());
            assertEquals(contaModelo.getTipo(), copia.getTipo());

            assertEquals(
                    contaModelo.getContaRedutora(),
                    copia.getContaRedutora()
            );

            assertEquals(
                    contaModelo.getClassificacaoBP(),
                    copia.getClassificacaoBP()
            );

            assertEquals(
                    contaModelo.getClassificacaoDRE(),
                    copia.getClassificacaoDRE()
            );

            // Verifica a hierarquia da conta.

            if (contaModelo.getContaPaiId() == null) {

                assertNull(copia.getContaPaiId());

            } else {

                ContaContabil paiCopiado =
                        contasCopiadasPorModeloId.get(
                                contaModelo.getContaPaiId()
                        );

                assertNotNull(
                        paiCopiado,
                        "A conta-pai deveria ter sido copiada."
                );

                assertEquals(
                        paiCopiado.getId(),
                        copia.getContaPaiId()
                );
            }

            // Verifica a relação entre conta redutora e reduzida.

            if (contaModelo.getContaReduzidaId() == null) {

                assertNull(copia.getContaReduzidaId());

            } else {

                ContaContabil reduzidaCopiada =
                        contasCopiadasPorModeloId.get(
                                contaModelo.getContaReduzidaId()
                        );

                assertNotNull(
                        reduzidaCopiada,
                        "A conta reduzida deveria ter sido copiada."
                );

                assertEquals(
                        reduzidaCopiada.getId(),
                        copia.getContaReduzidaId()
                );
            }
        }
    }

    @Test
    void naoDeveProvisionarQuandoUsuarioNaoExiste() {

        UUID usuarioId = UUID.randomUUID();
        UUID empresaId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> planoContasService.provisionar(empresaId, usuarioId)
        );

        assertEquals("Usuário não encontrado.", exception.getMessage());

        assertTrue(
                planoContasRepository.findByEmpresaId(empresaId).isEmpty()
        );
    }

    @Test
    void naoDeveProvisionarQuandoUsuarioEstaInativo() {

        Usuario usuario = criarUsuario();
        Empresa empresa = criarEmpresa(usuario);

        entityManager.createNativeQuery(
                        "UPDATE usuario SET ativo = false WHERE id = :id"
                )
                .setParameter("id", usuario.getId())
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planoContasService.provisionar(
                        empresa.getId(),
                        usuario.getId()
                )
        );

        assertEquals(
                "Não é permitido provisionar um plano para usuário inativo.",
                exception.getMessage()
        );

        assertTrue(
                planoContasRepository.findByEmpresaId(empresa.getId()).isEmpty()
        );
    }

    @Test
    void naoDeveProvisionarQuandoEmpresaNaoExiste() {

        Usuario usuario = criarUsuario();
        UUID empresaId = UUID.randomUUID();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> planoContasService.provisionar(
                        empresaId,
                        usuario.getId()
                )
        );

        assertEquals(
                "Empresa não encontrada ou não pertence ao usuário.",
                exception.getMessage()
        );

        assertTrue(
                planoContasRepository.findByEmpresaId(empresaId).isEmpty()
        );
    }

    @Test
    void naoDeveProvisionarEmpresaDeOutroUsuario() {

        Usuario usuarioAutenticado = criarUsuario();
        Usuario outroUsuario = criarUsuario();

        Empresa empresaDoOutroUsuario = criarEmpresa(outroUsuario);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> planoContasService.provisionar(
                        empresaDoOutroUsuario.getId(),
                        usuarioAutenticado.getId()
                )
        );

        assertEquals(
                "Empresa não encontrada ou não pertence ao usuário.",
                exception.getMessage()
        );

        assertTrue(
                planoContasRepository
                        .findByEmpresaId(empresaDoOutroUsuario.getId())
                        .isEmpty()
        );
    }

    @Test
    void naoDeveProvisionarQuandoEmpresaEstaInativa() {

        Usuario usuario = criarUsuario();
        Empresa empresa = criarEmpresa(usuario);

        entityManager.createNativeQuery(
                        "UPDATE empresa SET ativa = false WHERE id = :id"
                )
                .setParameter("id", empresa.getId())
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planoContasService.provisionar(
                        empresa.getId(),
                        usuario.getId()
                )
        );

        assertEquals(
                "Não é permitido provisionar plano para empresa inativa.",
                exception.getMessage()
        );

        assertTrue(
                planoContasRepository.findByEmpresaId(empresa.getId()).isEmpty()
        );
    }

    @Test
    void naoDeveProvisionarQuandoEmpresaEstaArquivada() {

        Usuario usuario = criarUsuario();
        Empresa empresa = criarEmpresa(usuario);

        entityManager.createNativeQuery(
                        """
                        UPDATE empresa
                        SET ativa = false,
                            arquivada_em = CURRENT_TIMESTAMP,
                            arquivada_por = :usuarioId,
                            motivo_arquivamento = :motivo
                        WHERE id = :id
                        """
                )
                .setParameter("usuarioId", usuario.getId())
                .setParameter("motivo", "Arquivamento para teste automatizado")
                .setParameter("id", empresa.getId())
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planoContasService.provisionar(
                        empresa.getId(),
                        usuario.getId()
                )
        );

        assertEquals(
                "Não é permitido provisionar plano para empresa arquivada.",
                exception.getMessage()
        );

        assertTrue(
                planoContasRepository.findByEmpresaId(empresa.getId()).isEmpty()
        );
    }


    @Test
    void naoDeveProvisionarDuasVezesParaAMesmaEmpresa() {

        Usuario usuario = criarUsuario();
        Empresa empresa = criarEmpresa(usuario);

        // Primeira tentativa: deve funcionar.
        PlanoContas primeiroPlano = planoContasService.provisionar(
                empresa.getId(),
                usuario.getId()
        );

        entityManager.flush();

        assertNotNull(primeiroPlano.getId());

        // Segunda tentativa: deve ser rejeitada.
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> planoContasService.provisionar(
                        empresa.getId(),
                        usuario.getId()
                )
        );

        assertEquals(
                "A empresa já possui um plano de contas.",
                exception.getMessage()
        );

        List<ContaContabil> contas = contaContabilRepository
                .findAllByPlanoContasIdOrderByCodigoAsc(
                        primeiroPlano.getId()
                );

        assertFalse(contas.isEmpty());

        assertEquals(
                primeiroPlano.getId(),
                planoContasRepository.findByEmpresaId(empresa.getId())
                        .orElseThrow()
                        .getId()
        );
    }

// Métodos auxiliares para os testes.

    private Usuario criarUsuario() {

        Usuario usuario = new Usuario(
                "Usuário de Teste",
                "teste-" + UUID.randomUUID() + "@lex.com",
                "hash-de-senha-de-teste"
        );

        return usuarioRepository.saveAndFlush(usuario);
    }

    private Empresa criarEmpresa(Usuario usuario) {

        Empresa empresa = new Empresa(
                usuario,
                "Empresa de Teste LTDA",
                "Empresa Teste",
                "11222333000181"
        );

        return empresaRepository.saveAndFlush(empresa);
    }

}
