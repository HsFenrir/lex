package br.com.lex.domain.contabil.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "empresa")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Empresa {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "razao_social", nullable = false, length = 200)
    private String razaoSocial;

    @Column(name = "nome_fantasia", length = 200)
    private String nomeFantasia;

    @Column(name = "cnpj", length = 14)
    private String cnpj;

    @Column(name = "ativa", nullable = false)
    private boolean ativa = true;

    @Column(name = "arquivada_em")
    private OffsetDateTime arquivadaEm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "arquivada_por")
    private Usuario arquivadaPor;

    @Column(name = "motivo_arquivamento", columnDefinition = "TEXT")
    private String motivoArquivamento;

    @Column(
            name = "criado_em",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private OffsetDateTime criadoEm;

    public Empresa(
            Usuario usuario,
            String razaoSocial,
            String nomeFantasia,
            String cnpj
    ) {
        this.usuario = usuario;
        this.razaoSocial = razaoSocial;
        this.nomeFantasia = nomeFantasia;
        this.cnpj = cnpj;
    }
}
