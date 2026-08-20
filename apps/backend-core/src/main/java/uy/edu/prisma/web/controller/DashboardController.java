package uy.edu.prisma.web.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uy.edu.prisma.application.DashboardService;
import uy.edu.prisma.web.dto.Dto.DashboardStatsDto;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private final DashboardService service;

  public DashboardController(DashboardService service) {
    this.service = service;
  }

  @GetMapping("/stats")
  public ResponseEntity<DashboardStatsDto> stats() {
    return ResponseEntity.ok(service.getStats());
  }
}
