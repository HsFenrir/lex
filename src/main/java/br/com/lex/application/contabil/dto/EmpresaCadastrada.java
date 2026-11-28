package br.com.lex.application.contabil.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EmpresaCadastrada(UUID id, String razaoSocial, String nomeFantasia, String cnpj,
                               boolean ativa, OffsetDateTime arquivadaEm, UUID arquivadaPor,
                               String motivoArquivamento) {
    public record Dados(String razaoSocial, String nomeFantasia, String cnpj) {}
}
