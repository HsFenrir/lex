package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.ModeloPlanoContas;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModeloPlanoContasRepository
        extends JpaRepository<ModeloPlanoContas, Integer> {
}