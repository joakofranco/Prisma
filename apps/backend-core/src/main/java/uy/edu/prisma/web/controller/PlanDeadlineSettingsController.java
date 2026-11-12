package uy.edu.prisma.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.PlanDeadlineSettingsService;
import uy.edu.prisma.web.dto.Dto.PlanDeadlinesDto;

/**
 * RF-PLN-03 — Plazos por defecto del plan de mejora. Leerlos lo puede hacer cualquier usuario
 * autenticado (el alta de una acción precarga la fecha con ellos); cambiarlos, solo PRISMA_ADMIN.
 */
@RestController
@RequestMapping("/api/settings/plan-deadlines")
public class PlanDeadlineSettingsController {

  private final PlanDeadlineSettingsService service;

  public PlanDeadlineSettingsController(PlanDeadlineSettingsService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<PlanDeadlinesDto> get() {
    return ResponseEntity.ok(service.get());
  }

  @PutMapping
  @PreAuthorize("hasRole('PRISMA_ADMIN')")
  public ResponseEntity<PlanDeadlinesDto> update(@RequestBody PlanDeadlinesDto dto) {
    return ResponseEntity.ok(service.update(dto));
  }
}
