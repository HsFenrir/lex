
package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.PlanoContabil;
import br.com.lex.domain.contabil.entity.ContaContabil;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import br.com.lex.domain.contabil.entity.ModeloPlanoContas;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.enums.TipoConta;
import br.com.lex.domain.contabil.repository.ContaContabilRepository;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import br.com.lex.domain.contabil.repository.ModeloContaContabilRepository;
import br.com.lex.domain.contabil.repository.ModeloPlanoContasRepository;
import br.com.lex.domain.contabil.repository.PlanoContasRepository;
import br.com.lex.domain.contabil.repository.UsuarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PlanoContasService {

    private final UsuarioRepository usuarioRepository;
    private final EmpresaRepository empresaRepository;
    private final ModeloPlanoContasRepository modeloPlanoContasRepository;
    private final ModeloContaContabilRepository modeloContaContabilRepository;
    private final PlanoContasRepository planoContasRepository;
    private final ContaContabilRepository contas;
    private final ConsultaContabil acesso;
    private final EntityManager entityManager;

    public PlanoContasService(
            UsuarioRepository usuarioRepository,
            EmpresaRepository empresaRepository,
            ModeloPlanoContasRepository modeloPlanoContasRepository,
            ModeloContaContabilRepository modeloContaContabilRepository,
            PlanoContasRepository planoContasRepository,
            EntityManager entityManager,
            ContaContabilRepository contas,
            ConsultaContabil acesso
    ) {
        this.usuarioRepository = usuarioRepository;
        this.empresaRepository = empresaRepository;
        this.modeloPlanoContasRepository = modeloPlanoContasRepository;
        this.modeloContaContabilRepository = modeloContaContabilRepository;
        this.planoContasRepository = planoContasRepository;
        this.entityManager = entityManager;
        this.contas = contas;
        this.acesso = acesso;
    }

    @Transactional(readOnly = true)
    public PlanoContabil consultar(UUID empresaId, UUID usuarioId) {
        acesso.usuarioAtivo(usuarioId);
        acesso.empresaDoUsuario(empresaId, usuarioId);
        PlanoContas plano = planoContasRepository.findByEmpresaId(empresaId)
                .orElseThrow(() -> new IllegalArgumentException("Plano de contas da empresa não encontrado."));
        List<PlanoContabil.Conta> detalhes = contas.findAllByPlanoContasIdOrderByCodigoAsc(plano.getId())
                .stream()
                .map(conta -> new PlanoContabil.Conta(conta.getId(), conta.getCodigo(), conta.getNome(),
                        conta.getTipo(), conta.getNatureza(), conta.getGrupo(), conta.getClassificacaoBP(),
                        conta.getClassificacaoDRE(), conta.getContaPaiId(), conta.getContaRedutora(),
                        conta.getContaReduzidaId(), conta.getAtiva(), conta.getModeloContaId()))
                .toList();
        return new PlanoContabil(plano.getId(), empresaId, plano.getModeloVersao(), detalhes);
    }

    /**
     * Cria o plano de contas de uma empresa copiando integralmente
     * o modelo padrão ativo.
     *
     * usuarioId é recebido explicitamente enquanto a autenticação
     * com Spring Security ainda não está implementada.
     */
    @Transactional
    public PlanoContas provisionar(UUID empresaId, UUID usuarioId) {

        if (empresaId == null || usuarioId == null) {
            throw new IllegalArgumentException(
                    "Empresa e usuário devem ser informados."
            );
        }

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Usuário não encontrado."
                ));

        if (!usuario.isAtivo()) {
            throw new IllegalStateException(
                    "Não é permitido provisionar um plano para usuário inativo."
            );
        }

        Empresa empresa = empresaRepository
                .findByIdAndUsuarioId(empresaId, usuarioId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Empresa não encontrada ou não pertence ao usuário."
                ));

        entityManager.refresh(empresa, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(usuario, LockModeType.PESSIMISTIC_READ);
        if (!usuarioId.equals(empresa.getUsuario().getId()) || !usuario.isAtivo()) {
            throw new IllegalStateException("A autorização para provisionar o plano foi alterada.");
        }

        if (empresa.getArquivadaEm() != null) {
            throw new IllegalStateException(
                    "Não é permitido provisionar plano para empresa arquivada."
            );
        }

        if (!empresa.isAtiva()) {
            throw new IllegalStateException(
                    "Não é permitido provisionar plano para empresa inativa."
            );
        }

        if (planoContasRepository.findByEmpresaId(empresaId).isPresent()) {
            throw new IllegalStateException(
                    "A empresa já possui um plano de contas."
            );
        }

        ModeloPlanoContas modelo = obterModeloAtivoUnico();

        List<ModeloContaContabil> contasModelo =
                modeloContaContabilRepository
                        .findAllByModeloVersaoOrderByCodigoAsc(
                                modelo.getVersao()
                        );

        if (contasModelo.isEmpty()) {
            throw new IllegalStateException(
                    "O modelo ativo não possui contas cadastradas."
            );
        }

        Map<UUID, ModeloContaContabil> contasPorId =
                indexarEValidarContas(contasModelo, modelo.getVersao());

        validarDependencias(contasPorId);

        PlanoContas plano = new PlanoContas(empresa, modelo);

        entityManager.persist(plano);

        Map<UUID, ContaContabil> contasCopiadas = new HashMap<>();
        Set<UUID> contasEmProcessamento = new HashSet<>();

        for (ModeloContaContabil contaModelo : contasModelo) {
            copiarContaRecursivamente(
                    contaModelo.getId(),
                    plano,
                    contasPorId,
                    contasCopiadas,
                    contasEmProcessamento
            );
        }

        if (contasCopiadas.size() != contasModelo.size()) {
            throw new IllegalStateException(
                    "A quantidade de contas copiadas diverge da quantidade de contas do modelo."
            );
        }

        entityManager.flush();
        return plano;
    }

    private ModeloPlanoContas obterModeloAtivoUnico() {

        List<ModeloPlanoContas> modelosAtivos =
                modeloPlanoContasRepository.findAllByAtivoTrue();

        if (modelosAtivos.isEmpty()) {
            throw new IllegalStateException(
                    "Não existe modelo de plano de contas ativo."
            );
        }

        if (modelosAtivos.size() > 1) {
            throw new IllegalStateException(
                    "Existe mais de um modelo de plano de contas ativo. "
                            + "A configuração precisa ser corrigida."
            );
        }

        return modelosAtivos.get(0);
    }

    private Map<UUID, ModeloContaContabil> indexarEValidarContas(
            List<ModeloContaContabil> contas,
            Integer versao
    ) {
        Map<UUID, ModeloContaContabil> mapa = new HashMap<>();
        Set<String> codigos = new HashSet<>();

        for (ModeloContaContabil conta : contas) {

            if (conta.getId() == null) {
                throw new IllegalStateException(
                        "O modelo contém conta sem identificador."
                );
            }

            if (!versao.equals(conta.getModeloVersao())) {
                throw new IllegalStateException(
                        "O modelo contém conta vinculada a versão diferente."
                );
            }

            if (mapa.putIfAbsent(conta.getId(), conta) != null) {
                throw new IllegalStateException(
                        "O modelo contém identificadores de conta duplicados."
                );
            }

            if (conta.getCodigo() == null
                    || !codigos.add(conta.getCodigo())) {
                throw new IllegalStateException(
                        "O modelo contém código de conta nulo ou duplicado."
                );
            }

            if (conta.getTipo() == null
                    || conta.getContaRedutora() == null) {
                throw new IllegalStateException(
                        "O modelo contém conta com classificação incompleta."
                );
            }
        }

        return mapa;
    }

    private void validarDependencias(
            Map<UUID, ModeloContaContabil> contasPorId
    ) {

        for (ModeloContaContabil conta : contasPorId.values()) {

            UUID paiId = conta.getContaPaiId();

            if (paiId != null) {
                ModeloContaContabil pai = contasPorId.get(paiId);

                if (pai == null) {
                    throw new IllegalStateException(
                            "A conta " + conta.getCodigo()
                                    + " referencia uma conta-pai inexistente no modelo."
                    );
                }

                if (pai.getTipo() != TipoConta.SINTETICA) {
                    throw new IllegalStateException(
                            "A conta-pai da conta " + conta.getCodigo()
                                    + " não é sintética."
                    );
                }
            }

            UUID reduzidaId = conta.getContaReduzidaId();

            if (Boolean.TRUE.equals(conta.getContaRedutora())
                    && reduzidaId == null) {
                throw new IllegalStateException(
                        "A conta redutora " + conta.getCodigo()
                                + " não informa qual conta reduz."
                );
            }

            if (Boolean.FALSE.equals(conta.getContaRedutora())
                    && reduzidaId != null) {
                throw new IllegalStateException(
                        "A conta " + conta.getCodigo()
                                + " não é redutora, mas referencia uma conta reduzida."
                );
            }

            if (reduzidaId != null && !contasPorId.containsKey(reduzidaId)) {
                throw new IllegalStateException(
                        "A conta " + conta.getCodigo()
                                + " referencia uma conta reduzida inexistente no modelo."
                );
            }
        }

        // Verifica ciclos considerando tanto a hierarquia quanto
        // as dependências entre contas redutoras e contas reduzidas.
        Set<UUID> visitadas = new HashSet<>();
        Set<UUID> emProcessamento = new HashSet<>();

        for (UUID contaId : contasPorId.keySet()) {
            validarAusenciaDeCiclos(
                    contaId,
                    contasPorId,
                    visitadas,
                    emProcessamento
            );
        }
    }

    private void validarAusenciaDeCiclos(
            UUID contaId,
            Map<UUID, ModeloContaContabil> contasPorId,
            Set<UUID> visitadas,
            Set<UUID> emProcessamento
    ) {

        if (visitadas.contains(contaId)) {
            return;
        }

        if (!emProcessamento.add(contaId)) {
            throw new IllegalStateException(
                    "O modelo contém dependência circular entre contas."
            );
        }

        ModeloContaContabil conta = contasPorId.get(contaId);

        if (conta.getContaPaiId() != null) {
            validarAusenciaDeCiclos(
                    conta.getContaPaiId(),
                    contasPorId,
                    visitadas,
                    emProcessamento
            );
        }

        if (conta.getContaReduzidaId() != null) {
            validarAusenciaDeCiclos(
                    conta.getContaReduzidaId(),
                    contasPorId,
                    visitadas,
                    emProcessamento
            );
        }

        emProcessamento.remove(contaId);
        visitadas.add(contaId);
    }

    private ContaContabil copiarContaRecursivamente(
            UUID contaModeloId,
            PlanoContas plano,
            Map<UUID, ModeloContaContabil> contasPorId,
            Map<UUID, ContaContabil> contasCopiadas,
            Set<UUID> contasEmProcessamento
    ) {

        ContaContabil existente = contasCopiadas.get(contaModeloId);

        if (existente != null) {
            return existente;
        }

        if (!contasEmProcessamento.add(contaModeloId)) {
            throw new IllegalStateException(
                    "Foi detectada dependência circular durante a cópia do plano."
            );
        }

        ModeloContaContabil contaModelo = contasPorId.get(contaModeloId);

        if (contaModelo == null) {
            throw new IllegalStateException(
                    "Conta não encontrada no modelo durante a cópia."
            );
        }

        UUID contaPaiCopiadaId = null;

        if (contaModelo.getContaPaiId() != null) {
            ContaContabil contaPaiCopiada = copiarContaRecursivamente(
                    contaModelo.getContaPaiId(),
                    plano,
                    contasPorId,
                    contasCopiadas,
                    contasEmProcessamento
            );

            contaPaiCopiadaId = contaPaiCopiada.getId();
        }

        UUID contaReduzidaCopiadaId = null;

        if (contaModelo.getContaReduzidaId() != null) {
            ContaContabil contaReduzidaCopiada = copiarContaRecursivamente(
                    contaModelo.getContaReduzidaId(),
                    plano,
                    contasPorId,
                    contasCopiadas,
                    contasEmProcessamento
            );

            contaReduzidaCopiadaId = contaReduzidaCopiada.getId();
        }

        ContaContabil contaCopiada = new ContaContabil(
                plano,
                contaModelo,
                contaPaiCopiadaId,
                contaReduzidaCopiadaId
        );

        entityManager.persist(contaCopiada);

        contasCopiadas.put(contaModeloId, contaCopiada);
        contasEmProcessamento.remove(contaModeloId);

        return contaCopiada;
    }
}
