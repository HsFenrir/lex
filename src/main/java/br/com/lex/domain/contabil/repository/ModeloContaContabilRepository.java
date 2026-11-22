package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.ModeloContaContabil;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ModeloContaContabilRepository
        extends JpaRepository<ModeloContaContabil, UUID> {

    List<ModeloContaContabil> findAllByModeloVersaoOrderByCodigoAsc(
            Integer modeloVersao
    );
}