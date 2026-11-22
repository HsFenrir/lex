package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.PlanoContas;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PlanoContasRepository
        extends JpaRepository<PlanoContas, UUID> {

    Optional<PlanoContas> findByEmpresaId(UUID empresaId);
}