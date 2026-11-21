package br.com.lex.domain.contabil.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "modelo_plano_contas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModeloPlanoContas {

    @Id
    @Column(name = "versao", nullable = false)
    private Integer versao;

    @Column(name = "descricao", nullable = false, length = 200)
    private String descricao;

    @Column(name = "ativo", nullable = false)
    private Boolean ativo;

    @Column(
            name = "criado_em",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime criadoEm;
}