package uy.edu.prisma.domain.repository;

/**
 * Fragmentos JPQL para buscar sin distinguir mayúsculas, tildes ni puntos (PRI-368). Son constantes
 * de compilación porque van dentro de {@code @Query}: se usan como {@code PRE + "u.email" + POST}.
 * El texto buscado se pliega igual en {@code uy.edu.prisma.application.SearchText}: ambos lados
 * tienen que usar la misma regla.
 */
final class SearchSql {

  /** Minúsculas, sin tildes ni puntos: «Dr. Pérez» y «dr perez» comparan igual. */
  static final String FOLD_PRE = "REPLACE(LOWER(FUNCTION('translate', ";

  static final String FOLD_POST =
      ", 'áàâäéèêëíìîïóòôöúùûüñçÁÀÂÄÉÈÊËÍÌÎÏÓÒÔÖÚÙÛÜÑÇ', 'aaaaeeeeiiiioooouuuuncAAAAEEEEIIIIOOOOUUUUNC')), '.', '')";

  /** Como FOLD pero ignorando también guiones y espacios (para RUT: «1.234.567-8» = «12345678»). */
  static final String ID_PRE = "REPLACE(REPLACE(" + FOLD_PRE;

  static final String ID_POST = FOLD_POST + ", '-', ''), ' ', '')";

  private SearchSql() {}
}
