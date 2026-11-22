package uy.edu.prisma.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogSubcategory;

final class MaturityCatalogModel {

  record LevelGroup(int level, List<UUID> controlIds) {}

  record Subcategory(
      UUID id,
      String name,
      UUID categoryId,
      String categoryName,
      UUID functionId,
      String functionName,
      int targetLevel,
      List<LevelGroup> levels) {

    // Modelo acumulativo: la subcategoria alcanza el nivel N solo si TODOS sus controles del
    // marco de nivel <= N estan marcados como cumplidos. Un control sin respuesta (los que el
    // perfil no exige, o los que aun no se respondieron) cuenta como NO cumplido y frena el
    // nivel -- misma semantica que la formula de la planilla. Iteramos solo sobre los niveles
    // que REALMENTE tienen algun control en esta subcategoria (no 1..targetLevel a ciegas):
    // si no hubiera controles de,
    // por ejemplo, nivel 1 o 2, filtrar "targetLevel <= N" da un stream vacío y allMatch()
    // sobre un stream vacío es true por vacuidad -- eso "regalaba" niveles sin haber
    // verificado nada real.
    int levelReached(Set<UUID> compliantControlIds) {
      int reached = 0;
      for (LevelGroup group : levels) {
        if (!compliantControlIds.containsAll(group.controlIds())) {
          break;
        }
        reached = group.level();
      }
      return reached;
    }
  }

  private final List<Subcategory> subcategories;

  private MaturityCatalogModel(List<Subcategory> subcategories) {
    this.subcategories = List.copyOf(subcategories);
  }

  List<Subcategory> subcategories() {
    return subcategories;
  }

  boolean isEmpty() {
    return subcategories.isEmpty();
  }

  // La madurez de una subcategoria se mide sobre TODOS los controles del marco ubicados en
  // ella, NO solo sobre los del perfil comunitario -- igual que la planilla oficial de Agesic
  // (formula de la columna "Nivel de Madurez" en la hoja "Madurez Subcategoria"). Un control
  // que el perfil no exige, si no tiene respuesta "cumple", frena el nivel: por eso NO se
  // filtra por perfil aca. El perfil sigue acotando que controles se pueden responder
  // (ver saveResponse); un control sin respuesta cuenta como no cumplido en el gate de abajo.
  //
  // Se agrupan TODOS los controles de la version por Subcategoria segun el mapeo CURADO
  // control<->subcategoria (CatalogControl.effectiveSubcategories(), ver V18): cada control
  // cuenta solo para las subcategorias en las que Agesic lo ubico. Para catalogos que no traen
  // ese mapeo (5.0 / 5.1) effectiveSubcategories() cae al mapeo Requisito->Subcategoria.
  // Se emite una fila por cada subcategoria con >=1 control mapeado (para MCU 5.x = 103); las
  // que el perfil no cubre quedan en currentLevel 0, igual que la hoja "Madurez Subcategoria".
  static MaturityCatalogModel from(List<CatalogControl> controls) {
    Map<UUID, CatalogSubcategory> subcategoriesById = new LinkedHashMap<>();
    Map<UUID, List<CatalogControl>> bySubcategory = new LinkedHashMap<>();
    for (CatalogControl control : controls) {
      for (CatalogSubcategory sub : control.effectiveSubcategories()) {
        subcategoriesById.putIfAbsent(sub.getId(), sub);
        bySubcategory.computeIfAbsent(sub.getId(), k -> new ArrayList<>()).add(control);
      }
    }

    List<Subcategory> result = new ArrayList<>(bySubcategory.size());
    for (Map.Entry<UUID, List<CatalogControl>> entry : bySubcategory.entrySet()) {
      CatalogSubcategory sub = subcategoriesById.get(entry.getKey());
      var category = sub.getCategory();
      var function = category.getFunction_();

      // Target level = el nivel mas alto definido entre los controles de esta subcategoria
      // (normalmente 4: el catalogo real solo define niveles 1-4).
      int targetLevel =
          entry.getValue().stream().mapToInt(CatalogControl::getTargetLevel).max().orElse(1);

      Map<Integer, List<UUID>> idsByLevel = new TreeMap<>();
      for (CatalogControl control : entry.getValue()) {
        idsByLevel
            .computeIfAbsent(control.getTargetLevel(), k -> new ArrayList<>())
            .add(control.getId());
      }
      List<LevelGroup> levels = new ArrayList<>(idsByLevel.size());
      idsByLevel.forEach((level, ids) -> levels.add(new LevelGroup(level, List.copyOf(ids))));

      result.add(
          new Subcategory(
              sub.getId(),
              sub.getName(),
              category.getId(),
              category.getName(),
              function.getId(),
              function.getName(),
              targetLevel,
              List.copyOf(levels)));
    }
    return new MaturityCatalogModel(result);
  }
}
