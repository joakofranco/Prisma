package uy.edu.prisma.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.AlertSettingsService;
import uy.edu.prisma.web.dto.Dto.AlertSettingsDto;
import uy.edu.prisma.web.dto.Dto.AlertTestResultDto;
import uy.edu.prisma.web.dto.Dto.UpdateAlertSettingsDto;

@RestController
@RequestMapping("/api/admin/alert-settings")
@PreAuthorize("hasRole('PRISMA_ADMIN')")
public class AlertSettingsController {

  private final AlertSettingsService service;

  public AlertSettingsController(AlertSettingsService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<AlertSettingsDto> get() {
    return ResponseEntity.ok(service.get());
  }

  @PutMapping
  public ResponseEntity<AlertSettingsDto> update(@RequestBody UpdateAlertSettingsDto dto) {
    return ResponseEntity.ok(service.update(dto));
  }

  @PostMapping("/test")
  public ResponseEntity<AlertTestResultDto> test() {
    return ResponseEntity.ok(service.test());
  }
}
