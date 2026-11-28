package br.com.lex.application.contabil.service;

import br.com.lex.application.contabil.dto.EmpresaCadastrada;
import br.com.lex.application.contabil.dto.EmpresaCadastrada.Dados;
import br.com.lex.domain.contabil.entity.Empresa;
import br.com.lex.domain.contabil.entity.PlanoContas;
import br.com.lex.domain.contabil.entity.Usuario;
import br.com.lex.domain.contabil.repository.EmpresaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmpresaService {

    private final EmpresaRepository empresas;
    private final ConsultaContabil acesso;
    private final PlanoContasService planos;
    private final CalendarioContabilService calendario;
    private final AuditoriaCadastroContabil auditoria;
    private final EntityManager em;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EmpresaCadastrada cadastrar(UUID usuarioId, Dados dados, YearMonth inicioEscrituracao) {
        Dados validos = validar(dados);
        calendario.validarInicio(inicioEscrituracao);
        Usuario titular = acesso.usuarioAtivo(usuarioId);
        em.refresh(titular, LockModeType.PESSIMISTIC_READ);
        acesso.validarUsuarioAtivo(titular);

        Empresa empresa = empresas.saveAndFlush(new Empresa(titular, validos.razaoSocial(),
                validos.nomeFantasia(), validos.cnpj()));
        PlanoContas plano = planos.provisionar(empresa.getId(), usuarioId);
        auditoria.registrar(empresa.getId(), usuarioId, "PLANO_CONTAS", plano.getId(), "PROVISIONAR_PLANO_CONTAS",
                null, Map.of("modeloVersao", plano.getModeloVersao()));
        calendario.criarInicial(empresa.getId(), usuarioId, inicioEscrituracao);
        return registrar(empresa, usuarioId, "CADASTRAR_EMPRESA", null);
    }

    public List<EmpresaCadastrada> listar(UUID usuarioId) {
        acesso.usuarioAtivo(usuarioId);
        return empresas.findAllByUsuarioIdOrderByRazaoSocialAscIdAsc(usuarioId).stream()
                .map(EmpresaService::resumo).toList();
    }

    public EmpresaCadastrada consultar(UUID empresaId, UUID usuarioId) {
        acesso.usuarioAtivo(usuarioId);
        return resumo(acesso.empresaDoUsuario(empresaId, usuarioId));
    }

    @Transactional
    public EmpresaCadastrada atualizar(UUID empresaId, UUID usuarioId, Dados dados) {
        Dados validos = validar(dados);
        Empresa empresa = acesso.bloquearEmpresa(empresaId, usuarioId, LockModeType.PESSIMISTIC_WRITE);
        acesso.validarEmpresaAtiva(empresa);
        EmpresaCadastrada anterior = resumo(empresa);
        empresa.atualizarDados(validos.razaoSocial(), validos.nomeFantasia(), validos.cnpj());
        return registrar(empresa, usuarioId, "ATUALIZAR_EMPRESA", anterior);
    }

    @Transactional
    public EmpresaCadastrada arquivar(UUID empresaId, UUID usuarioId, String motivo) {
        String motivoValido = texto(motivo, true, "Motivo do arquivamento", Integer.MAX_VALUE);
        Empresa empresa = acesso.bloquearEmpresa(empresaId, usuarioId, LockModeType.PESSIMISTIC_WRITE);
        acesso.validarEmpresaAtiva(empresa);
        EmpresaCadastrada anterior = resumo(empresa);
        empresa.arquivar(empresa.getUsuario(), OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS), motivoValido);
        return registrar(empresa, usuarioId, "ARQUIVAR_EMPRESA", anterior);
    }

    @Transactional
    public EmpresaCadastrada reativar(UUID empresaId, UUID usuarioId) {
        Empresa empresa = acesso.bloquearEmpresa(empresaId, usuarioId, LockModeType.PESSIMISTIC_WRITE);
        if (empresa.getArquivadaEm() == null) {
            throw new IllegalStateException("Somente empresas arquivadas podem ser reativadas.");
        }
        EmpresaCadastrada anterior = resumo(empresa);
        empresa.reativar();
        return registrar(empresa, usuarioId, "REATIVAR_EMPRESA", anterior);
    }

    private EmpresaCadastrada registrar(Empresa empresa, UUID usuarioId, String acao, EmpresaCadastrada anterior) {
        em.flush();
        EmpresaCadastrada atual = resumo(empresa);
        if (!atual.equals(anterior)) {
            auditoria.registrar(empresa.getId(), usuarioId, "EMPRESA", empresa.getId(), acao, anterior, atual);
        }
        return atual;
    }

    private static EmpresaCadastrada resumo(Empresa empresa) {
        Usuario responsavel = empresa.getArquivadaPor();
        return new EmpresaCadastrada(empresa.getId(), empresa.getRazaoSocial(), empresa.getNomeFantasia(),
                empresa.getCnpj(), empresa.isAtiva(), empresa.getArquivadaEm(),
                responsavel == null ? null : responsavel.getId(), empresa.getMotivoArquivamento());
    }

    private Dados validar(Dados dados) {
        if (dados == null) {
            throw new IllegalArgumentException("Informe os dados da empresa.");
        }
        String razao = texto(dados.razaoSocial(), true, "Razão social", 200);
        String fantasia = texto(dados.nomeFantasia(), false, "Nome fantasia", 200);
        String cnpj = texto(dados.cnpj(), false, "CNPJ", 14);
        if (cnpj != null && !cnpj.matches("[0-9]{14}")) {
            throw new IllegalArgumentException("O CNPJ deve conter 14 dígitos.");
        }
        return new Dados(razao, fantasia, cnpj);
    }

    private String texto(String valor, boolean obrigatorio, String campo, int limite) {
        if (valor == null || valor.isBlank()) {
            if (obrigatorio) {
                throw new IllegalArgumentException(campo + " deve ser informado.");
            }
            return null;
        }
        String texto = valor.strip();
        if (texto.indexOf(0) >= 0 || texto.codePointCount(0, texto.length()) > limite) {
            throw new IllegalArgumentException(campo + " inválido.");
        }
        return texto;
    }
}
