package uy.edu.prisma.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uy.edu.prisma.infrastructure.ClamAvClient;

@Component
public class EvidenceRescanScheduler {

  private static final Logger log = LoggerFactory.getLogger(EvidenceRescanScheduler.class);

  private final EvidenceService evidenceService;

  public EvidenceRescanScheduler(EvidenceService evidenceService) {
    this.evidenceService = evidenceService;
  }

  @Scheduled(
      fixedDelayString = "${prisma.clamav.rescan-interval-ms:60000}",
      initialDelayString = "${prisma.clamav.rescan-interval-ms:60000}")
  public void rescanQuarantined() {
    for (UUID id : evidenceService.quarantinedIds()) {
      try {
        if (evidenceService.rescanQuarantined(id) == ClamAvClient.Status.ERROR) {
          return;
        }
      } catch (Exception e) {
        log.warn("No se pudo reanalizar la evidencia {}: {}", id, e.getMessage());
      }
    }
  }
}
