package uy.edu.prisma.application;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.*;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.*;
import uy.edu.prisma.web.dto.Dto.CreateEvaluationDto;
import uy.edu.prisma.web.dto.Dto.EvaluationDto;
import uy.edu.prisma.web.dto.Dto.EvaluationResponseDto;
import uy.edu.prisma.web.dto.Dto.MaturityResultDto;
import uy.edu.prisma.web.dto.Dto.PaginatedDto;
import uy.edu.prisma.web.dto.Dto.SaveResponseDto;

@Service
@Transactional
public class EvaluationService {

  private final EvaluationRepository evalRepo;
  private final EvaluationResponseRepository respRepo;
  private final MaturityResultRepository matRepo;
  private final OrganizationRepository orgRepo;
  private final CatalogControlRepository controlRepo;
  private final CatalogFunctionRepository functionRepo;
  private final CatalogSubcategoryRepository subcategoryRepo;
  private final CatalogVersionRepository versionRepo;
  private final CommunityProfileRepository profileRepo;
  private final UserRepository userRepo;
  private final CurrentUserService currentUser;
  private final AuditLogService auditLog;

  public EvaluationService(
      EvaluationRepository evalRepo,
      EvaluationResponseRepository respRepo,
      MaturityResultRepository matRepo,
      OrganizationRepository orgRepo,
      CatalogControlRepository controlRepo,
      CatalogFunctionRepository functionRepo,
      CatalogSubcategoryRepository subcategoryRepo,
      CatalogVersionRepository versionRepo,
      CommunityProfileRepository profileRepo,
      UserRepository userRepo,
      CurrentUserService currentUser,
      AuditLogService auditLog) {
    this.evalRepo = evalRepo;
    this.respRepo = respRepo;
    this.matRepo = matRepo;
    this.orgRepo = orgRepo;
    this.controlRepo = controlRepo;
    this.functionRepo = functionRepo;
    this.subcategoryRepo = subcategoryRepo;
    this.versionRepo = versionRepo;
    this.profileRepo = profileRepo;
    this.userRepo = userRepo;
    this.currentUser = currentUser;
    this.auditLog = auditLog;
  }

  // PRISMA_ADMIN y el evaluador general (INTERNAL_EVALUATOR sin organizaciones) listan sin
  // restriccion. Todos los demas estan acotados a SUS organizaciones (una o varias, ver
  // User.organizations): si piden una organizacion puntual debe ser una de las suyas (si no, 403),
  // y si no piden ninguna ven la union de todas las suyas (nunca "todas las del sistema").
  @Transactional(readOnly = true)
  public PaginatedDto<EvaluationDto> list(String status, UUID orgId, int page, int pageSize) {
    PageRequest pr = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
    Evaluation.Status statusFilter = status != null ? Evaluation.Status.valueOf(status) : null;

    Page<Evaluation> result;
    if (currentUser.hasAllOrganizationsAccess()) {
      result = queryByOrgAndStatus(orgId, statusFilter, pr);
    } else if (orgId != null) {
      currentUser.assertOrganizationAccess(orgId);
      result = queryByOrgAndStatus(orgId, statusFilter, pr);
    } else {
      Set<UUID> ownOrgIds = currentUser.organizationIds();
      if (ownOrgIds.isEmpty()) {
        return new PaginatedDto<>(List.of(), 0, page, pageSize);
      }
      result =
          statusFilter != null
              ? evalRepo.findByOrganizationIdInAndStatusOrderByCreatedAtDesc(
                  ownOrgIds, statusFilter, pr)
              : evalRepo.findByOrganizationIdInAndStatusNotOrderByCreatedAtDesc(
                  ownOrgIds, Evaluation.Status.ARCHIVED, pr);
    }

    // Auditores asignados por organización, resueltos en UNA sola consulta para toda la página en
    // vez de una por fila (evita N+1): toDto() sólo hace un lookup en el mapa ya armado.
    Set<UUID> orgIds =
        result.getContent().stream()
            .map(e -> e.getOrganization().getId())
            .collect(Collectors.toSet());
    Map<UUID, List<String>> auditorsByOrg = auditorNamesByOrganization(orgIds);

    return new PaginatedDto<>(
        result.getContent().stream().map(e -> toDto(e, auditorsByOrg)).toList(),
        result.getTotalElements(),
        page,
        pageSize);
  }

  // Nombre completo de cada AUDITOR con alguna de estas organizaciones entre sus
  // auditedOrganizations, agrupados por organización (ver el comentario en
  // UserRepository.findAuditorsForOrganizations).
  private Map<UUID, List<String>> auditorNamesByOrganization(Set<UUID> orgIds) {
    if (orgIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<String>> byOrg = new HashMap<>();
    for (User auditor : userRepo.findAuditorsForOrganizations(orgIds)) {
      String name = (auditor.getFirstName() + " " + auditor.getLastName()).trim();
      for (Organization org : auditor.getOrganizations()) {
        if (orgIds.contains(org.getId())) {
          byOrg.computeIfAbsent(org.getId(), k -> new ArrayList<>()).add(name);
        }
      }
    }
    return byOrg;
  }

  private Page<Evaluation> queryByOrgAndStatus(
      UUID orgId, Evaluation.Status status, PageRequest pr) {
    if (status != null && orgId != null) {
      return evalRepo.findByOrganizationIdAndStatusOrderByCreatedAtDesc(orgId, status, pr);
    } else if (status != null) {
      return evalRepo.findByStatusOrderByCreatedAtDesc(status, pr);
    } else if (orgId != null) {
      // Sin filtro de estado se OCULTAN las archivadas (descartadas / cerradas): se las consulta
      // filtrando explícitamente por ARCHIVED.
      return evalRepo.findByOrganizationIdAndStatusNotOrderByCreatedAtDesc(
          orgId, Evaluation.Status.ARCHIVED, pr);
    } else {
      return evalRepo.findByStatusNotOrderByCreatedAtDesc(Evaluation.Status.ARCHIVED, pr);
    }
  }

  @Transactional(readOnly = true)
  public EvaluationDto getById(UUID id) {
    Evaluation eval =
        evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evaluación", id));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return toDto(eval, auditorNamesByOrganization(Set.of(eval.getOrganization().getId())));
  }

  public EvaluationDto create(CreateEvaluationDto dto) {
    currentUser.assertOrganizationAccess(dto.organizationId());
    Organization org =
        orgRepo
            .findById(dto.organizationId())
            .orElseThrow(() -> new ResourceNotFoundException("Organización", dto.organizationId()));
    // Una organización inactiva conserva su historial (consultable) pero no admite evaluaciones
    // nuevas.
    if (!Boolean.TRUE.equals(org.getEnabled())) {
      throw new InvalidRequestException(
          "No se puede crear una evaluación para la organización «"
              + org.getName()
              + "» porque está inactiva");
    }
    String catalogVersion = dto.catalogVersion() != null ? dto.catalogVersion() : "5.0";
    CommunityProfile profile = null;
    if (dto.communityProfileId() != null) {
      profile =
          profileRepo
              .findById(dto.communityProfileId())
              .orElseThrow(
                  () ->
                      new ResourceNotFoundException(
                          "Perfil comunitario", dto.communityProfileId()));
      if (!profile.getCatalogVersion().equals(catalogVersion)) {
        throw new InvalidRequestException(
            "El perfil comunitario elegido no pertenece a la versión de catálogo "
                + catalogVersion);
      }
    }
    Evaluation eval =
        Evaluation.builder()
            .name(dto.name())
            .organization(org)
            .catalogVersion(catalogVersion)
            .communityProfile(profile)
            .status(Evaluation.Status.DRAFT)
            .createdBy(currentUser.currentUser().orElse(null))
            .build();
    EvaluationDto result =
        toDto(evalRepo.save(eval), auditorNamesByOrganization(Set.of(org.getId())));
    auditLog.record(
        "CREATE",
        "evaluation:" + result.id(),
        "{\"name\":\""
            + result.name()
            + "\",\"organizationId\":\""
            + result.organizationId()
            + "\"}");
    return result;
  }

  /**
   * Cambia el perfil comunitario de una evaluación que TODAVÍA NO COMENZÓ: estado Borrador y sin
   * ninguna respuesta cargada. Con respuestas, cambiar el perfil dejaría controles ya respondidos
   * fuera del alcance (o controles nuevos sin responder) y los resultados dejarían de corresponder
   * a lo evaluado; en ese caso hay que crear otra evaluación. {@code null} = catálogo completo. El
   * perfil elegido debe ser de la misma versión de catálogo que la evaluación.
   */
  public EvaluationDto changeProfile(UUID id, UUID profileId) {
    Evaluation eval =
        evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evaluación", id));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    if (eval.getStatus() != Evaluation.Status.DRAFT || evalRepo.countResponses(id) > 0) {
      throw new InvalidRequestException(
          "Solo se puede cambiar el perfil de una evaluación sin comenzar (en Borrador y sin"
              + " respuestas cargadas)");
    }
    CommunityProfile profile = null;
    if (profileId != null) {
      profile =
          profileRepo
              .findById(profileId)
              .orElseThrow(() -> new ResourceNotFoundException("Perfil comunitario", profileId));
      if (!profile.getCatalogVersion().equals(eval.getCatalogVersion())) {
        throw new InvalidRequestException(
            "El perfil comunitario elegido no pertenece a la versión de catálogo "
                + eval.getCatalogVersion());
      }
    }
    eval.setCommunityProfile(profile);
    EvaluationDto result =
        toDto(
            evalRepo.save(eval),
            auditorNamesByOrganization(Set.of(eval.getOrganization().getId())));
    auditLog.record(
        "UPDATE",
        "evaluation:" + id,
        "{\"communityProfileId\":" + (profileId == null ? "null" : "\"" + profileId + "\"") + "}");
    return result;
  }

  // Ciclo de vida documentado: Borrador -> En Curso -> Lista para Auditoría -> En Auditoría ->
  // Aprobada / Devuelta -> Archivada (Devuelta vuelve a En Curso para corregir). Antes esto
  // aceptaba CUALQUIER valor del enum sin mirar el estado actual -- una evaluación ya ARCHIVED
  // (pensada como registro histórico inmutable) se podía revertir a DRAFT con un solo PATCH. Las
  // claves ausentes del mapa (ARCHIVED) son estados terminales: no admiten ninguna transición.
  private static final Map<Evaluation.Status, Set<Evaluation.Status>> ALLOWED_TRANSITIONS =
      Map.of(
          // ARCHIVED (descartar / cerrar) se permite desde cualquier estado que NO esté en manos
          // del
          // auditor: Borrador, En Curso, Devuelta y Aprobada. READY_FOR_AUDIT e IN_AUDIT quedan
          // afuera a propósito (hay una auditoría en marcha: primero se aprueba o se devuelve).
          Evaluation.Status.DRAFT,
              Set.of(Evaluation.Status.IN_PROGRESS, Evaluation.Status.ARCHIVED),
          Evaluation.Status.IN_PROGRESS,
              Set.of(Evaluation.Status.READY_FOR_AUDIT, Evaluation.Status.ARCHIVED),
          Evaluation.Status.RETURNED,
              Set.of(Evaluation.Status.IN_PROGRESS, Evaluation.Status.ARCHIVED),
          Evaluation.Status.READY_FOR_AUDIT, Set.of(Evaluation.Status.IN_AUDIT),
          Evaluation.Status.IN_AUDIT,
              Set.of(Evaluation.Status.APPROVED, Evaluation.Status.RETURNED),
          Evaluation.Status.APPROVED, Set.of(Evaluation.Status.ARCHIVED));

  public EvaluationDto updateStatus(UUID id, String status) {
    Evaluation eval =
        evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evaluación", id));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    Evaluation.Status current = eval.getStatus();
    Evaluation.Status target = Evaluation.Status.valueOf(status);
    if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
      throw new InvalidRequestException(
          "No se puede pasar de " + current + " a " + target + ": transición no permitida");
    }
    assertRoleCanTransitionTo(target);
    eval.setStatus(target);
    EvaluationDto result =
        toDto(
            evalRepo.save(eval),
            auditorNamesByOrganization(Set.of(eval.getOrganization().getId())));
    auditLog.record("UPDATE_STATUS", "evaluation:" + id, "{\"status\":\"" + status + "\"}");
    return result;
  }

  // Quién puede disparar cada transición: del lado de autoevaluación (DRAFT/IN_PROGRESS/RETURNED)
  // se acepta tanto ORG_RESPONSIBLE (el comentario de @PreAuthorize en el controller documenta
  // que es quien mueve todo ese lado) como INTERNAL_EVALUATOR (EVALUATION_STATUS_CONFIG en el
  // frontend marca a INTERNAL_EVALUATOR como responsable mientras la evaluación está IN_PROGRESS,
  // es decir, quien decide cuándo está lista para enviar a auditoría) -- las dos fuentes no
  // coincidían entre sí (ni con el código de EvaluationDetailView.vue, que antes solo ofrecía los
  // botones a INTERNAL_EVALUATOR y nunca a ORG_RESPONSIBLE, pese a que el controller SÍ lo
  // permitía), así que acá se admiten ambos roles en vez de forzar una lectura sobre la otra.
  // AUDITOR mueve el lado de auditoría (READY_FOR_AUDIT/IN_AUDIT). PRISMA_ADMIN siempre puede,
  // igual que en assertOrganizationAccess. Antes ninguna de estas reglas se exigía acá: solo se
  // chequeaba el rol para ENTRAR al endpoint, nunca cuál transición puntual le correspondía a cada
  // uno.
  private void assertRoleCanTransitionTo(Evaluation.Status target) {
    if (currentUser.isPrismaAdmin()) {
      return;
    }
    boolean allowed =
        switch (target) {
          case IN_PROGRESS, READY_FOR_AUDIT ->
              currentUser.hasRole(UserRole.ORG_RESPONSIBLE)
                  || currentUser.hasRole(UserRole.INTERNAL_EVALUATOR);
          case IN_AUDIT, APPROVED, RETURNED -> currentUser.isAuditor();
          // Archivar: el responsable de la organización (además de PRISMA_ADMIN, que siempre
          // puede).
          case ARCHIVED -> currentUser.hasRole(UserRole.ORG_RESPONSIBLE);
          default -> false;
        };
    if (!allowed) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Su rol no puede realizar esta transición de estado");
    }
  }

  /**
   * De los ids pedidos, los de evaluaciones que se pueden ELIMINAR ahora: accesibles para el
   * usuario y sin ningún dato colgando (ver {@link #delete}). Lo usa la interfaz para mostrar el
   * botón de eliminar solo donde corresponde. Solo PRISMA_ADMIN y ORG_RESPONSIBLE (quienes pueden
   * borrar); para el resto devuelve vacío.
   */
  @Transactional(readOnly = true)
  public List<UUID> deletableIds(Collection<UUID> ids) {
    if (ids == null
        || ids.isEmpty()
        || !(currentUser.isPrismaAdmin() || currentUser.hasRole(UserRole.ORG_RESPONSIBLE))) {
      return List.of();
    }
    List<UUID> accessible = new ArrayList<>();
    for (Evaluation e : evalRepo.findAllById(ids)) {
      try {
        currentUser.assertOrganizationAccess(e.getOrganization().getId());
        accessible.add(e.getId());
      } catch (org.springframework.security.access.AccessDeniedException ignored) {
        // Una evaluación de otra organización no se informa (ni siquiera que existe).
      }
    }
    if (accessible.isEmpty()) {
      return List.of();
    }
    Set<UUID> withData = new HashSet<>(evalRepo.findIdsWithData(accessible));
    return accessible.stream().filter(id -> !withData.contains(id)).toList();
  }

  public void delete(UUID id) {
    Evaluation eval =
        evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evaluación", id));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    // Respuestas, resultados de madurez, evidencias, observaciones de auditoría y planes de mejora
    // cuelgan de la evaluación con ON DELETE CASCADE: borrarla los destruiría en silencio (y
    // dejaría archivos huérfanos en el almacenamiento). Solo se permite borrar una evaluación
    // vacía; si ya
    // tiene contenido, se archiva (estado ARCHIVED) para conservar el historial.
    List<String> dependents = new ArrayList<>();
    addIfAny(dependents, evalRepo.countResponses(id), "respuestas");
    addIfAny(dependents, evalRepo.countEvidence(id), "evidencias");
    addIfAny(dependents, evalRepo.countAuditObservations(id), "observaciones de auditoría");
    addIfAny(dependents, evalRepo.countImprovementPlans(id), "planes de mejora");
    addIfAny(dependents, evalRepo.countMaturityResults(id), "resultados de madurez");
    if (!dependents.isEmpty()) {
      throw new ConflictException(
          "No se puede eliminar la evaluación porque tiene datos asociados ("
              + String.join(", ", dependents)
              + "). Archívela en su lugar para conservar el historial.");
    }
    evalRepo.deleteById(id);
    auditLog.record("DELETE", "evaluation:" + id, null);
  }

  private static void addIfAny(List<String> out, long count, String label) {
    if (count > 0) out.add(count + " " + label);
  }

  // ---- Responses ----
  @Transactional(readOnly = true)
  public List<EvaluationResponseDto> getResponses(UUID evaluationId) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return respRepo.findByEvaluationId(evaluationId).stream().map(this::toRespDto).toList();
  }

  public EvaluationResponseDto saveResponse(UUID evaluationId, SaveResponseDto dto) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    if (!eval.isSelfAssessmentEditable()) {
      // Antes esto no se chequeaba: una evaluación ya ARCHIVED o APPROVED (pensadas como registro
      // histórico cerrado) seguía aceptando respuestas que sobrescribían las ya cargadas.
      throw new InvalidRequestException(
          "No se pueden cargar respuestas: la evaluación está en estado "
              + eval.getStatus()
              + ", que ya no admite edición");
    }
    CatalogControl control =
        controlRepo
            .findById(dto.controlId())
            .orElseThrow(() -> new ResourceNotFoundException("Control", dto.controlId()));
    if (eval.getCommunityProfile() != null
        && eval.getCommunityProfile().getControls().stream()
            .noneMatch(c -> c.getId().equals(control.getId()))) {
      throw new InvalidRequestException(
          "El control "
              + control.getCode()
              + " no pertenece al perfil comunitario de esta evaluación");
    }

    EvaluationResponse resp =
        respRepo
            .findByEvaluationIdAndControlId(evaluationId, dto.controlId())
            .orElse(new EvaluationResponse());

    resp.setEvaluation(eval);
    resp.setControl(control);
    resp.setCompliant(Boolean.TRUE.equals(dto.compliant()));
    resp.setObservations(dto.observations());
    resp.setRespondedBy(currentUser.currentUser().orElse(null));
    resp.setRespondedAt(java.time.OffsetDateTime.now());

    EvaluationResponseDto result = toRespDto(respRepo.save(resp));
    auditLog.record(
        "SAVE_RESPONSE",
        "evaluation:" + evaluationId,
        "{\"controlId\":\"" + dto.controlId() + "\",\"compliant\":" + resp.getCompliant() + "}");
    return result;
  }

  // ---- Maturity Calculation ----
  @Transactional(readOnly = true)
  public List<MaturityResultDto> getResults(UUID evaluationId) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    return matRepo.findByEvaluationId(evaluationId).stream()
        .map(
            m ->
                new MaturityResultDto(
                    m.getFunctionId(),
                    m.getFunctionName(),
                    m.getCategoryId(),
                    m.getCategoryName(),
                    m.getSubcategoryId(),
                    m.getSubcategoryName(),
                    m.getCurrentLevel(),
                    m.getTargetLevel(),
                    m.getGap()))
        .toList();
  }

  public void calculateMaturity(UUID evaluationId) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());

    // Delete old results
    matRepo.deleteAllInBatch(matRepo.findByEvaluationId(evaluationId));

    // La madurez de una subcategoria se mide sobre TODOS los controles del marco ubicados en
    // ella, NO solo sobre los del perfil comunitario -- igual que la planilla oficial de Agesic
    // (formula de la columna "Nivel de Madurez" en la hoja "Madurez Subcategoria"). Un control
    // que el perfil no exige, si no tiene respuesta "cumple", frena el nivel: por eso NO se
    // filtra por perfil aca. El perfil sigue acotando que controles se pueden responder
    // (ver saveResponse); un control sin respuesta cuenta como no cumplido en el gate de abajo.
    List<CatalogControl> controls = controlRepo.findByVersionOrdered(eval.getCatalogVersion());

    // Get responses
    List<EvaluationResponse> responses = respRepo.findByEvaluationId(evaluationId);
    Map<UUID, EvaluationResponse> responseMap =
        responses.stream().collect(Collectors.toMap(r -> r.getControl().getId(), r -> r));

    // Se agrupan TODOS los controles de la version por Subcategoria segun el mapeo CURADO
    // control<->subcategoria (CatalogControl.effectiveSubcategories(), ver V18): cada control
    // cuenta solo para las subcategorias en las que Agesic lo ubico. Para catalogos que no traen
    // ese mapeo (5.0 / 5.1) effectiveSubcategories() cae al mapeo Requisito->Subcategoria.
    // Se emite una fila por cada subcategoria con >=1 control mapeado (para MCU 5.x = 103); las
    // que el perfil no cubre quedan en currentLevel 0, igual que la hoja "Madurez Subcategoria".
    Map<UUID, CatalogSubcategory> subcategoriesById = new LinkedHashMap<>();
    Map<UUID, List<CatalogControl>> bySubcategory = new LinkedHashMap<>();
    for (CatalogControl control : controls) {
      for (CatalogSubcategory sub : control.effectiveSubcategories()) {
        subcategoriesById.putIfAbsent(sub.getId(), sub);
        bySubcategory.computeIfAbsent(sub.getId(), k -> new ArrayList<>()).add(control);
      }
    }

    List<MaturityResult> results = new ArrayList<>();
    for (Map.Entry<UUID, List<CatalogControl>> entry : bySubcategory.entrySet()) {
      var sub = subcategoriesById.get(entry.getKey());
      var cat = sub.getCategory();
      var func = cat.getFunction_();

      // Target level = el nivel mas alto definido entre los controles de esta subcategoria
      // (normalmente 4: el catalogo real solo define niveles 1-4).
      int targetLevel =
          entry.getValue().stream().mapToInt(CatalogControl::getTargetLevel).max().orElse(1);

      // Modelo acumulativo: la subcategoria alcanza el nivel N solo si TODOS sus controles del
      // marco de nivel <= N estan marcados como cumplidos. Un control sin respuesta (los que el
      // perfil no exige, o los que aun no se respondieron) cuenta como NO cumplido y frena el
      // nivel -- misma semantica que la formula de la planilla. Iteramos solo sobre los niveles
      // que REALMENTE tienen algun control en esta subcategoria (no 1..targetLevel a ciegas):
      // si no hubiera controles de,
      // por ejemplo, nivel 1 o 2, filtrar "targetLevel <= N" da un stream vacío y allMatch()
      // sobre un stream vacío es true por vacuidad -- eso "regalaba" niveles sin haber
      // verificado nada real.
      List<Integer> levelsPresent =
          entry.getValue().stream()
              .map(CatalogControl::getTargetLevel)
              .distinct()
              .sorted()
              .toList();
      int currentLevel = 0;
      for (int level : levelsPresent) {
        final int upTo = level;
        boolean allCompliantUpToLevel =
            entry.getValue().stream()
                .filter(c -> c.getTargetLevel() <= upTo)
                .allMatch(
                    c -> {
                      EvaluationResponse r = responseMap.get(c.getId());
                      return r != null && Boolean.TRUE.equals(r.getCompliant());
                    });
        if (allCompliantUpToLevel) {
          currentLevel = level;
        } else {
          break;
        }
      }

      results.add(
          MaturityResult.builder()
              .evaluation(eval)
              .functionId(func.getId())
              .functionName(func.getName())
              .categoryId(cat.getId())
              .categoryName(cat.getName())
              .subcategoryId(sub.getId())
              .subcategoryName(sub.getName())
              .currentLevel(currentLevel)
              .targetLevel(targetLevel)
              .gap(Math.max(0, targetLevel - currentLevel))
              .build());
    }

    matRepo.saveAll(results);

    // Madurez global: promedio del currentLevel sobre TODAS las subcategorias de la version del
    // catalogo (para MCU 5.x = 103), tratando como 0 las que el perfil no cubre -- igual que la
    // planilla oficial de Agesic (hoja "Resumen general"). Antes se promediaba solo sobre las
    // subcategorias con respuesta, lo que inflaba el numero frente a la planilla.
    // Sigue siendo null solo si NO se cargo ninguna respuesta todavia (evaluacion sin empezar);
    // nivel 0 con respuestas es un resultado real, no "sin calcular".
    long totalSubcategories = subcategoryRepo.countByCatalogVersion(eval.getCatalogVersion());
    boolean hasAnyResponse = !responses.isEmpty();
    Integer globalMaturity =
        (!hasAnyResponse || totalSubcategories == 0)
            ? null
            : (int)
                Math.round(
                    results.stream().mapToInt(MaturityResult::getCurrentLevel).sum()
                        / (double) totalSubcategories);
    eval.setGlobalMaturity(globalMaturity);
    evalRepo.save(eval);
  }

  private EvaluationDto toDto(Evaluation e, Map<UUID, List<String>> auditorsByOrg) {
    User creator = e.getCreatedBy();
    return new EvaluationDto(
        e.getId(),
        e.getName(),
        e.getOrganization().getId(),
        e.getOrganization().getName(),
        e.getCatalogVersion(),
        e.getCommunityProfile() != null ? e.getCommunityProfile().getId() : null,
        e.getCommunityProfile() != null ? e.getCommunityProfile().getName() : null,
        e.getStatus().name(),
        e.getGlobalMaturity(),
        creator != null ? creator.getId() : null,
        e.getCreatedAt(),
        e.getUpdatedAt(),
        creator != null ? (creator.getFirstName() + " " + creator.getLastName()).trim() : null,
        auditorsByOrg.getOrDefault(e.getOrganization().getId(), List.of()));
  }

  private EvaluationResponseDto toRespDto(EvaluationResponse r) {
    return new EvaluationResponseDto(
        r.getId(),
        r.getEvaluation().getId(),
        r.getControl().getId(),
        r.getCompliant(),
        r.getObservations(),
        r.getRespondedBy() != null ? r.getRespondedBy().getId() : null,
        r.getRespondedAt());
  }
}
