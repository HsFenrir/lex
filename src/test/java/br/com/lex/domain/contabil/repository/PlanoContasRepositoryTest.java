
package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ModeloPlanoContas;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PlanoContasRepositoryTest {

    @Autowired
    private PlanoContasRepository planoContasRepository;

    @Autowired
    private ModeloPlanoContasRepository modeloPlanoContasRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void devePersistirERecuperarPlanoContasComSeusRelacionamentos() {

        // Arrange: cria e persiste um usuário.
        Usuario usuario = new Usuario(
                "Usuário de Teste",
                "teste-" + UUID.randomUUID() + "@lex.com",
                "hash-senha-teste"
        );

        entityManager.persist(usuario);
        entityManager.flush();

        // Arrange: cria e persiste uma empresa.
        Empresa empresa = new Empresa(
                usuario,
                "Empresa de Teste Ltda.",
                "Empresa Teste",
                "11222333000181"
        );

        entityManager.persist(empresa);
        entityManager.flush();

        // Arrange: recupera o modelo padrão carregado pela V3.
        ModeloPlanoContas modelo = modeloPlanoContasRepository
                .findById(1)
                .orElseThrow(() -> new AssertionError(
                        "Modelo padrão de plano de contas não encontrado."
                ));

        // Act: cria e persiste o plano da empresa.
        PlanoContas plano = new PlanoContas(empresa, modelo);

        PlanoContas planoSalvo =
                planoContasRepository.saveAndFlush(plano);

        assertNotNull(planoSalvo.getId());

        // Limpa o contexto para obrigar uma nova leitura do banco.
        entityManager.clear();

        // Act: recupera o plano pelo identificador.
        PlanoContas planoRecuperado = planoContasRepository
                .findById(planoSalvo.getId())
                .orElseThrow(() -> new AssertionError(
                        "Plano de contas não foi recuperado."
                ));

        // Assert: verifica os dados persistidos.
        assertEquals(empresa.getId(), planoRecuperado.getEmpresaId());
        assertEquals(modelo.getVersao(), planoRecuperado.getModeloVersao());

        // Assert: verifica os relacionamentos.
        assertNotNull(planoRecuperado.getEmpresa());
        assertEquals(
                empresa.getId(),
                planoRecuperado.getEmpresa().getId()
        );

        assertNotNull(planoRecuperado.getModeloPlanoContas());
        assertEquals(
                modelo.getVersao(),
                planoRecuperado.getModeloPlanoContas().getVersao()
        );

        // Assert: verifica a consulta derivada do repositório.
        assertTrue(
                planoContasRepository
                        .findByEmpresaId(empresa.getId())
                        .isPresent()
        );
    }
}
