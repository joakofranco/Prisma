package uy.edu.prisma.application;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.*;
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
    this.versionRepo = versionRepo;
    this.profileRepo = profileRepo;
    this.userRepo = userRepo;
    this.currentUser = currentUser;
    this.auditLog = auditLog;
  }

  // Los roles acotados a un tenant (ORG_RESPONSIBLE, INTERNAL_EVALUATOR, VIEWER) solo pueden
  // listar evaluaciones de su propia organizacion, sin importar que orgId se les pida: si piden
  // otra organizacion se rechaza, y si no piden ninguna se fuerza a la propia (nunca "todas").
  // PRISMA_ADMIN conserva el comportamiento anterior (sin restriccion). AUDITOR ya NO es un rol
  // global (ver CurrentUserService): esta acotado a las organizaciones que se le asignaron: si
  // pide una organizacion puntual debe ser una de las suyas, y si no pide ninguna ve la union de
  // TODAS las que puede auditar (nunca "todas las del sistema").
  @Transactional(readOnly = true)
  public PaginatedDto<EvaluationDto> list(String status, UUID orgId, int page, int pageSize) {
    PageRequest pr = PageRequest.of(page, pageSize, Sort.by("createdAt").descending());
    Evaluation.Status statusFilter = status != null ? Evaluation.Status.valueOf(status) : null;

    Page<Evaluation> result;
    if (currentUser.isPrismaAdmin()) {
      result = queryByOrgAndStatus(orgId, statusFilter, pr);
    } else if (currentUser.isAuditor()) {
      if (orgId != null) {
        currentUser.assertOrganizationAccess(orgId);
        result = queryByOrgAndStatus(orgId, statusFilter, pr);
      } else {
        Set<UUID> auditedOrgIds = currentUser.auditedOrganizationIds();
        if (auditedOrgIds.isEmpty()) {
          return new PaginatedDto<>(List.of(), 0, page, pageSize);
        }
        result =
            statusFilter != null
                ? evalRepo.findByOrganizationIdInAndStatusOrderByCreatedAtDesc(
                    auditedOrgIds, statusFilter, pr)
                : evalRepo.findByOrganizationIdInOrderByCreatedAtDesc(auditedOrgIds, pr);
      }
    } else {
      UUID tenantId = currentUser.currentTenantId();
      if (tenantId == null) {
        return new PaginatedDto<>(List.of(), 0, page, pageSize);
      }
      currentUser.assertOrganizationAccess(orgId != null ? orgId : tenantId);
      result = queryByOrgAndStatus(tenantId, statusFilter, pr);
    }

    // Auditores asignados por organización, resueltos en UNA sola consulta para toda la página en
    // vez de una por fila (evita N+1): toDto() sólo hace un lookup en el mapa ya armado.
    Set<UUID> orgIds =
        result.getContent().stream().map(e -> e.getOrganization().getId()).collect(Collectors.toSet());
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
      for (Organization org : auditor.getAuditedOrganizations()) {
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
      return evalRepo.findByOrganizationIdOrderByCreatedAtDesc(orgId, pr);
    } else {
      return evalRepo.findAllByOrderByCreatedAtDesc(pr);
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

  // Ciclo de vida documentado: Borrador -> En Curso -> Lista para Auditoría -> En Auditoría ->
  // Aprobada / Devuelta -> Archivada (Devuelta vuelve a En Curso para corregir). Antes esto
  // aceptaba CUALQUIER valor del enum sin mirar el estado actual -- una evaluación ya ARCHIVED
  // (pensada como registro histórico inmutable) se podía revertir a DRAFT con un solo PATCH. Las
  // claves ausentes del mapa (ARCHIVED) son estados terminales: no admiten ninguna transición.
  private static final Map<Evaluation.Status, Set<Evaluation.Status>> ALLOWED_TRANSITIONS =
      Map.of(
          Evaluation.Status.DRAFT, Set.of(Evaluation.Status.IN_PROGRESS),
          Evaluation.Status.IN_PROGRESS, Set.of(Evaluation.Status.READY_FOR_AUDIT),
          Evaluation.Status.RETURNED, Set.of(Evaluation.Status.IN_PROGRESS),
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
        toDto(evalRepo.save(eval), auditorNamesByOrganization(Set.of(eval.getOrganization().getId())));
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
          default -> false; // ARCHIVED y cualquier otro: solo PRISMA_ADMIN
        };
    if (!allowed) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Su rol no puede realizar esta transición de estado");
    }
  }

  public void delete(UUID id) {
    Evaluation eval =
        evalRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Evaluación", id));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    evalRepo.deleteById(id);
    auditLog.record("DELETE", "evaluation:" + id, null);
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

    // Controles de la version del catalogo, acotados al perfil comunitario si la evaluacion
    // tiene uno asignado -- si no, se calcula sobre el catalogo completo (comportamiento previo).
    List<CatalogControl> controls = controlRepo.findByVersionOrdered(eval.getCatalogVersion());
    if (eval.getCommunityProfile() != null) {
      Set<UUID> profileControlIds =
          eval.getCommunityProfile().getControls().stream()
              .map(CatalogControl::getId)
              .collect(Collectors.toSet());
      controls = controls.stream().filter(c -> profileControlIds.contains(c.getId())).toList();
    }

    // Get responses
    List<EvaluationResponse> responses = respRepo.findByEvaluationId(evaluationId);
    Map<UUID, EvaluationResponse> responseMap =
        responses.stream().collect(Collectors.toMap(r -> r.getControl().getId(), r -> r));

    // Group by subcategory and calculate. Un Requisito puede pertenecer a varias Subcategorias
    // (ver CatalogRequirementSubcategory), asi que un mismo Control puede terminar en mas de un
    // balde -- se sigue "pooleando" (mismo criterio de corte acumulativo) en CADA Subcategoria a
    // la que aporte, sin promediar por Requisito por separado.
    Map<UUID, CatalogSubcategory> subcategoriesById = new LinkedHashMap<>();
    Map<UUID, List<CatalogControl>> bySubcategory = new LinkedHashMap<>();
    for (CatalogControl control : controls) {
      for (CatalogSubcategory sub : control.getRequirement().getSubcategories()) {
        subcategoriesById.putIfAbsent(sub.getId(), sub);
        bySubcategory.computeIfAbsent(sub.getId(), k -> new ArrayList<>()).add(control);
      }
    }

    List<MaturityResult> results = new ArrayList<>();
    // Subcategorias con al menos una respuesta cargada -- para el promedio global (mas abajo),
    // ver el comentario ahi de por que no se promedia sobre TODAS las subcategorias del catalogo.
    Set<UUID> touchedSubcategoryIds = new HashSet<>();
    for (Map.Entry<UUID, List<CatalogControl>> entry : bySubcategory.entrySet()) {
      var sub = subcategoriesById.get(entry.getKey());
      var cat = sub.getCategory();
      var func = cat.getFunction_();

      // Target level = el nivel mas alto definido entre los controles de esta subcategoria
      // (normalmente 4: el catalogo real solo define niveles 1-4).
      int targetLevel =
          entry.getValue().stream().mapToInt(CatalogControl::getTargetLevel).max().orElse(1);

      // Modelo acumulativo: la subcategoria alcanza el nivel N solo si TODOS sus controles de
      // nivel <= N (entre todos los Requisitos que agrupa) estan marcados como cumplidos -- no
      // alcanza con que UN control este cumplido, como pasaba con el maximo autoevaluado del
      // catalogo de prueba anterior. Iteramos solo sobre los niveles que REALMENTE tienen algun
      // control en esta subcategoria (no 1..targetLevel a ciegas): si no hubiera controles de,
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
      if (entry.getValue().stream().anyMatch(c -> responseMap.containsKey(c.getId()))) {
        touchedSubcategoryIds.add(sub.getId());
      }
    }

    matRepo.saveAll(results);

    // Update global maturity: promedio solo sobre las subcategorias con alguna respuesta
    // cargada, no sobre TODAS las del catalogo/perfil. El catalogo real MCU 5.0 tiene 103
    // subcategorias -- promediar currentLevel=0 de las que todavia no se empezaron a evaluar
    // diluye el promedio a ~0 (globalMaturity queda en null) incluso con evaluaciones que ya
    // completaron varias subcategorias enteras, dando una lectura de "sin progreso" enganosa.
    List<MaturityResult> touchedResults =
        results.stream().filter(r -> touchedSubcategoryIds.contains(r.getSubcategoryId())).toList();
    // Nivel 0 es un resultado real (se evaluó todo lo tocado y no alcanzó ni el primer nivel),
    // distinto de "todavía no se calculó nada" -- solo lo segundo debe guardarse como null. Antes
    // ambos casos daban null porque un promedio que redondeaba a 0 se pisaba con null, así que un
    // resultado 0 legítimo se mostraba igual que uno nunca calculado ("—" en el frontend).
    Integer globalMaturity =
        touchedResults.isEmpty()
            ? null
            : (int)
                Math.round(
                    touchedResults.stream().mapToInt(MaturityResult::getCurrentLevel).average().orElse(0));
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
