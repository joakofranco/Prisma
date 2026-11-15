package uy.edu.prisma.web.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uy.edu.prisma.application.EvaluationOverviewService;
import uy.edu.prisma.web.dto.Dto.EvaluationOverviewDto;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationOverviewController {

  private final EvaluationOverviewService service;

  public EvaluationOverviewController(EvaluationOverviewService service) {
    this.service = service;
  }

  @GetMapping("/{id}/overview")
  public ResponseEntity<EvaluationOverviewDto> getOverview(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getOverview(id));
  }
}
