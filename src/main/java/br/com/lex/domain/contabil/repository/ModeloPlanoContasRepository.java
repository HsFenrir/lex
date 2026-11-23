package br.com.lex.domain.contabil.repository;

import br.com.lex.domain.contabil.entity.ModeloPlanoContas;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ModeloPlanoContasRepository
        extends JpaRepository<ModeloPlanoContas, Integer> {

    List<ModeloPlanoContas> findAllByAtivoTrue();
}
