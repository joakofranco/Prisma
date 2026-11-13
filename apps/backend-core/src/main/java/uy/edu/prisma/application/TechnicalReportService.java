package uy.edu.prisma.application;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.edu.prisma.domain.entity.AuditControlVerdict;
import uy.edu.prisma.domain.entity.AuditObservation;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.entity.CatalogSubcategory;
import uy.edu.prisma.domain.entity.Evaluation;
import uy.edu.prisma.domain.entity.EvaluationResponse;
import uy.edu.prisma.domain.entity.Evidence;
import uy.edu.prisma.domain.entity.MaturityResult;
import uy.edu.prisma.domain.entity.User;
import uy.edu.prisma.domain.exception.ConflictException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.AuditControlVerdictRepository;
import uy.edu.prisma.domain.repository.AuditObservationRepository;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.domain.repository.EvaluationRepository;
import uy.edu.prisma.domain.repository.EvaluationResponseRepository;
import uy.edu.prisma.domain.repository.EvidenceRepository;
import uy.edu.prisma.domain.repository.MaturityResultRepository;
import uy.edu.prisma.web.dto.Dto.EvaluationAuditCycleEventDto;

/**
 * RF-REP-02 (PRI-310) — Reporte técnico detallado en PDF: el desglose control por control que
 * respalda el nivel de madurez de cada subcategoría (cumplimiento, comentario del evaluador,
 * veredicto del auditor, evidencias y observaciones), con explicaciones de cómo leerlo, por qué
 * cada subcategoría está en su nivel y qué la frena, y la trazabilidad de la aprobación.
 *
 * <p>Mismo criterio que el reporte final: solo para evaluaciones Aprobadas, o Archivadas después de
 * haber sido aprobadas (ver {@link ReportService#isReportable}). Solo lee: no modifica nada.
 */
@Service
@Transactional(readOnly = true)
public class TechnicalReportService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

  private static final Color BRAND = new Color(30, 58, 138); // blue-900
  private static final Color BRAND_LIGHT = new Color(191, 219, 254); // blue-200
  private static final Color SLATE_900 = new Color(15, 23, 42);
  private static final Color SLATE_700 = new Color(51, 65, 85);
  private static final Color SLATE_500 = new Color(71, 85, 105); // slate-600: contraste AA
  private static final Color SLATE_200 = new Color(226, 232, 240);
  private static final Color SLATE_100 = new Color(241, 245, 249);
  private static final Color SLATE_50 = new Color(248, 250, 252);
  private static final Color GREEN_BG = new Color(209, 250, 229);
  private static final Color GREEN_FG = new Color(6, 95, 70);
  private static final Color ORANGE_BG = new Color(255, 237, 213);
  private static final Color ORANGE_ROW = new Color(255, 247, 237);
  private static final Color ORANGE_FG = new Color(154, 52, 18);
  private static final Color RED_BG = new Color(254, 226, 226);
  private static final Color RED_FG = new Color(185, 28, 28);
  private static final Color AMBER_BG = new Color(254, 243, 199);
  private static final Color AMBER_FG = new Color(146, 64, 14);
  private static final Color BLUE_BG = new Color(219, 234, 254);
  private static final Color BLUE_FG = new Color(30, 64, 175);

  private final EvaluationRepository evalRepo;
  private final MaturityResultRepository matRepo;
  private final CatalogControlRepository controlRepo;
  private final EvaluationResponseRepository responseRepo;
  private final AuditControlVerdictRepository verdictRepo;
  private final AuditObservationRepository obsRepo;
  private final EvidenceRepository evidenceRepo;
  private final AuditLogService auditLog;
  private final CurrentUserService currentUser;

  public TechnicalReportService(
      EvaluationRepository evalRepo,
      MaturityResultRepository matRepo,
      CatalogControlRepository controlRepo,
      EvaluationResponseRepository responseRepo,
      AuditControlVerdictRepository verdictRepo,
      AuditObservationRepository obsRepo,
      EvidenceRepository evidenceRepo,
      AuditLogService auditLog,
      CurrentUserService currentUser) {
    this.evalRepo = evalRepo;
    this.matRepo = matRepo;
    this.controlRepo = controlRepo;
    this.responseRepo = responseRepo;
    this.verdictRepo = verdictRepo;
    this.obsRepo = obsRepo;
    this.evidenceRepo = evidenceRepo;
    this.auditLog = auditLog;
    this.currentUser = currentUser;
  }

  public byte[] generate(UUID evaluationId) {
    Evaluation eval =
        evalRepo
            .findById(evaluationId)
            .orElseThrow(() -> new ResourceNotFoundException("Evaluación", evaluationId));
    currentUser.assertOrganizationAccess(eval.getOrganization().getId());
    if (!ReportService.isReportable(eval)) {
      throw new ConflictException(
          "El reporte técnico sólo puede exportarse con la evaluación 'Aprobada', o 'Archivada'"
              + " después de haber sido aprobada (estado actual: "
              + ReportService.statusLabel(eval.getStatus())
              + ")");
    }
    Data data = collect(eval);

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Document doc = new Document(PageSize.A4, 40, 40, 60, 52);
      PdfWriter writer = PdfWriter.getInstance(doc, out);
      PdfA.prepare(
          writer,
          "Reporte técnico detallado — " + eval.getName(),
          OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
      writer.setPageEvent(new PageDecorator(eval));
      doc.open();
      PdfA.addOutputIntent(writer);
      writeCover(doc, eval, data);
      writeFunctionMaturity(doc, data);
      writeCategoryMaturity(doc, writer, data);
      writeDetail(doc, data);
      writeAnnexes(doc, eval, data);
      doc.close();
      return out.toByteArray();
    } catch (IOException | DocumentException e) {
      throw new IllegalStateException("No se pudo generar el reporte técnico", e);
    }
  }

  // =====================================================================================
  // Datos
  // =====================================================================================

  enum Compliance {
    MET,
    NOT_MET,
    NO_ANSWER
  }

  record ControlRow(
      CatalogControl control,
      Compliance compliance,
      String comment,
      AuditControlVerdict.Verdict verdict,
      List<String> evidenceRefs) {}

  record SubcategoryBlock(
      String function,
      String category,
      String categoryCode,
      String title,
      int current,
      int target,
      List<ControlRow> controls,
      List<AuditObservation> observations) {}

  record EvidenceRow(String ref, Evidence evidence) {}

  record Data(
      List<SubcategoryBlock> blocks,
      List<ControlRow> controls,
      List<AuditObservation> observations,
      List<EvidenceRow> evidences,
      List<EvaluationAuditCycleEventDto> trail) {}

  private Data collect(Evaluation eval) {
    UUID id = eval.getId();

    Set<UUID> profileControlIds =
        eval.getCommunityProfile() != null
            ? eval.getCommunityProfile().getControls().stream()
                .map(CatalogControl::getId)
                .collect(Collectors.toSet())
            : null;
    List<CatalogControl> controls =
        controlRepo.findByVersionOrdered(eval.getCatalogVersion()).stream()
            .filter(c -> profileControlIds == null || profileControlIds.contains(c.getId()))
            .toList();

    Map<UUID, EvaluationResponse> responses = new HashMap<>();
    for (EvaluationResponse r : responseRepo.findByEvaluationId(id)) {
      responses.putIfAbsent(r.getControl().getId(), r);
    }
    Map<UUID, AuditControlVerdict.Verdict> verdicts = new HashMap<>();
    for (AuditControlVerdict v : verdictRepo.findByEvaluationId(id)) {
      verdicts.putIfAbsent(v.getControl().getId(), v.getVerdict());
    }

    // Referencias E-01, E-02… en orden de carga: la tabla de controles cita la referencia y el
    // Anexo 5 trae el detalle (archivo, huella, quién y cuándo).
    List<Evidence> evidences =
        new ArrayList<>(evidenceRepo.findByEvaluationIdOrderByUploadedAtDesc(id));
    evidences.sort(
        Comparator.comparing(
            Evidence::getUploadedAt, Comparator.nullsLast(Comparator.naturalOrder())));
    List<EvidenceRow> evidenceRows = new ArrayList<>();
    Map<UUID, List<String>> refsByControl = new HashMap<>();
    for (int i = 0; i < evidences.size(); i++) {
      Evidence e = evidences.get(i);
      String ref = String.format("E-%02d", i + 1);
      evidenceRows.add(new EvidenceRow(ref, e));
      if (e.getControl() != null) {
        refsByControl.computeIfAbsent(e.getControl().getId(), k -> new ArrayList<>()).add(ref);
      }
    }

    Map<UUID, ControlRow> rows = new LinkedHashMap<>();
    for (CatalogControl c : controls) {
      EvaluationResponse r = responses.get(c.getId());
      Compliance compliance =
          r == null
              ? Compliance.NO_ANSWER
              : Boolean.TRUE.equals(r.getCompliant()) ? Compliance.MET : Compliance.NOT_MET;
      String comment =
          r != null && r.getObservations() != null && !r.getObservations().isBlank()
              ? r.getObservations().strip()
              : null;
      rows.put(
          c.getId(),
          new ControlRow(
              c,
              compliance,
              comment,
              verdicts.get(c.getId()),
              refsByControl.getOrDefault(c.getId(), List.of())));
    }

    List<AuditObservation> observations =
        new ArrayList<>(obsRepo.findByEvaluationIdOrderByCreatedAtDesc(id));
    observations.sort(
        Comparator.comparing(
            AuditObservation::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

    Map<UUID, MaturityResult> resultsBySub = new HashMap<>();
    for (MaturityResult m : matRepo.findByEvaluationId(id)) {
      resultsBySub.put(m.getSubcategoryId(), m);
    }

    // Un control puede contar en varias subcategorías (ver CatalogControl.effectiveSubcategories):
    // aparece en cada una, igual que en el cálculo de madurez.
    Map<UUID, CatalogSubcategory> subs = new LinkedHashMap<>();
    Map<UUID, List<ControlRow>> rowsBySub = new LinkedHashMap<>();
    for (CatalogControl c : controls) {
      for (CatalogSubcategory sub : c.effectiveSubcategories()) {
        subs.putIfAbsent(sub.getId(), sub);
        rowsBySub.computeIfAbsent(sub.getId(), k -> new ArrayList<>()).add(rows.get(c.getId()));
      }
    }

    List<SubcategoryBlock> blocks = new ArrayList<>();
    for (Map.Entry<UUID, CatalogSubcategory> entry : subs.entrySet()) {
      CatalogSubcategory sub = entry.getValue();
      List<ControlRow> subRows = rowsBySub.get(entry.getKey());
      subRows.sort(
          Comparator.comparing((ControlRow r) -> r.control().getTargetLevel())
              .thenComparing(r -> r.control().getCode()));
      Set<UUID> subControlIds =
          subRows.stream().map(r -> r.control().getId()).collect(Collectors.toSet());
      List<AuditObservation> subObs =
          observations.stream()
              .filter(
                  o ->
                      (o.getSubcategory() != null && o.getSubcategory().getId().equals(sub.getId()))
                          || (o.getControl() != null
                              && subControlIds.contains(o.getControl().getId())))
              .toList();
      MaturityResult m = resultsBySub.get(sub.getId());
      int maxLevel = subRows.stream().mapToInt(r -> r.control().getTargetLevel()).max().orElse(1);
      blocks.add(
          new SubcategoryBlock(
              sub.getCategory().getFunction_().getName(),
              sub.getCategory().getName(),
              sub.getCategory().getCode(),
              sub.getCode() != null && !sub.getCode().isBlank()
                  ? sub.getCode() + " · " + sub.getName()
                  : sub.getName(),
              m != null && m.getCurrentLevel() != null ? m.getCurrentLevel() : 0,
              m != null && m.getTargetLevel() != null ? m.getTargetLevel() : maxLevel,
              subRows,
              subObs));
    }

    return new Data(
        blocks,
        List.copyOf(rows.values()),
        observations,
        evidenceRows,
        auditLog.getEvaluationAuditTrail(id));
  }

  // =====================================================================================
  // Explicaciones (modelo acumulativo, ver EvaluationService.calculateMaturity)
  // =====================================================================================

  /** Controles que impiden subir al nivel siguiente: no cumplidos de nivel <= actual + 1. */
  static List<ControlRow> blocking(SubcategoryBlock b) {
    int next = b.current() + 1;
    return b.controls().stream()
        .filter(r -> r.control().getTargetLevel() <= next && r.compliance() != Compliance.MET)
        .toList();
  }

  static String whyText(SubcategoryBlock b) {
    if (b.current() >= b.target()) {
      return "Alcanza su nivel objetivo: cumple todos los controles " + upTo(b.target()) + ".";
    }
    int next = b.current() + 1;
    List<ControlRow> blocking = blocking(b);
    if (blocking.isEmpty()) {
      return "Según el último cálculo de madurez no alcanza el nivel " + next + ".";
    }
    StringBuilder text = new StringBuilder();
    if (b.current() == 0) {
      text.append("No alcanza el nivel 1");
    } else {
      text.append("Cumple todos los controles ")
          .append(upTo(b.current()))
          .append(". No alcanza el nivel ")
          .append(next);
    }
    text.append(" porque ")
        .append(codes(blocking))
        .append(blocking.size() == 1 ? " no está cumplido." : " no están cumplidos.");
    long higherMet =
        b.controls().stream()
            .filter(r -> r.control().getTargetLevel() > next && r.compliance() == Compliance.MET)
            .count();
    if (higherMet > 0) {
      text.append(" Tiene ")
          .append(higherMet)
          .append(higherMet == 1 ? " control" : " controles")
          .append(" de nivel superior cumplido")
          .append(higherMet == 1 ? "" : "s")
          .append(", que el modelo acumulativo no cuenta hasta cerrar el nivel ")
          .append(next)
          .append(".");
    }
    return text.toString();
  }

  static String nextStepText(SubcategoryBlock b) {
    List<ControlRow> blocking = blocking(b);
    if (b.current() >= b.target() || blocking.isEmpty()) {
      return null;
    }
    return "Para subir al nivel "
        + (b.current() + 1)
        + ": cumplir y respaldar con evidencia "
        + codes(blocking)
        + ".";
  }

  /** «de nivel 1» o «de nivel 1 a N». */
  private static String upTo(int level) {
    return level <= 1 ? "de nivel 1" : "de nivel 1 a " + level;
  }

  /** «1 cumplido» / «3 cumplidos». */
  private static String plural(long n, String singular, String pluralForm) {
    return n + " " + (n == 1 ? singular : pluralForm);
  }

  private static String codes(List<ControlRow> rows) {
    List<String> codes = rows.stream().map(r -> r.control().getCode()).distinct().toList();
    if (codes.size() <= 1) {
      return String.join("", codes);
    }
    return String.join(", ", codes.subList(0, codes.size() - 1))
        + " y "
        + codes.get(codes.size() - 1);
  }

  // =====================================================================================
  // Portada y resumen
  // =====================================================================================

  private void writeCover(Document doc, Evaluation eval, Data data) throws DocumentException {
    PdfPTable banner = new PdfPTable(new float[] {3f, 1.4f});
    banner.setWidthPercentage(100);
    PdfPCell left = new PdfPCell();
    left.setBackgroundColor(BRAND);
    left.setBorder(Rectangle.NO_BORDER);
    left.setPadding(16);
    left.addElement(new Paragraph("PRISMA", font(FontFactory.HELVETICA_BOLD, 20, Color.WHITE)));
    left.addElement(
        new Paragraph(
            "Plataforma de evaluación de madurez en ciberseguridad · Marco AGESIC MCU "
                + eval.getCatalogVersion(),
            font(FontFactory.HELVETICA, 8.5f, BRAND_LIGHT)));
    banner.addCell(left);
    PdfPCell right = new PdfPCell();
    right.setBackgroundColor(BRAND);
    right.setBorder(Rectangle.NO_BORDER);
    right.setPadding(16);
    right.setVerticalAlignment(Element.ALIGN_BOTTOM);
    Paragraph tag =
        new Paragraph(
            "Documento técnico\nUso interno · Confidencial",
            font(FontFactory.HELVETICA, 8, BRAND_LIGHT));
    tag.setAlignment(Element.ALIGN_RIGHT);
    right.addElement(tag);
    banner.addCell(right);
    doc.add(banner);

    Paragraph kicker =
        new Paragraph("REPORTE TÉCNICO DETALLADO", font(FontFactory.HELVETICA_BOLD, 8.5f, BRAND));
    kicker.setSpacingBefore(16);
    doc.add(kicker);
    Paragraph title =
        new Paragraph(
            eval.getName() + " — " + eval.getOrganization().getName(),
            font(FontFactory.HELVETICA_BOLD, 18, SLATE_900));
    title.setSpacingBefore(4);
    doc.add(title);
    doc.add(
        spaced(
            new Paragraph(
                "Desglose control por control del cumplimiento, el veredicto del auditor, las"
                    + " observaciones y las evidencias que respaldan el nivel de madurez de cada"
                    + " subcategoría.",
                font(FontFactory.HELVETICA, 9.5f, SLATE_500)),
            4,
            12));

    PdfPTable info = new PdfPTable(4);
    info.setWidthPercentage(100);
    infoCell(info, "ORGANIZACIÓN", eval.getOrganization().getName());
    infoCell(info, "CATÁLOGO", "MCU " + eval.getCatalogVersion());
    infoCell(
        info,
        "PERFIL",
        eval.getCommunityProfile() != null
            ? eval.getCommunityProfile().getName()
            : "Catálogo completo");
    infoCell(info, "ESTADO", ReportService.statusLabel(eval.getStatus()));
    infoCell(
        info, "APROBADA", eval.getApprovedAt() != null ? eval.getApprovedAt().format(DATE) : "—");
    infoCell(info, "APROBADA POR", nameOf(eval.getApprovedBy(), "—"));
    infoCell(
        info,
        "MADUREZ GLOBAL",
        eval.getGlobalMaturity() != null && eval.getGlobalMaturity() > 0
            ? "Nivel " + eval.getGlobalMaturity()
            : "Sin nivel");
    infoCell(info, "GENERADO", OffsetDateTime.now().format(DATE_TIME));
    doc.add(info);

    writeSummary(doc, data);
    writeHowToRead(doc);
  }

  private void writeSummary(Document doc, Data data) throws DocumentException {
    sectionTitle(doc, "1. Resumen técnico");
    int total = data.controls().size();
    long met = count(data.controls(), Compliance.MET);
    long notMet = count(data.controls(), Compliance.NOT_MET);
    long noAnswer = count(data.controls(), Compliance.NO_ANSWER);

    PdfPTable kpis = new PdfPTable(4);
    kpis.setWidthPercentage(100);
    kpis.setSpacingAfter(6);
    kpiCell(kpis, String.valueOf(total), "Controles evaluados", SLATE_50, SLATE_900);
    kpiCell(kpis, met + " · " + percent(met, total), "Cumplidos", GREEN_BG, GREEN_FG);
    kpiCell(kpis, notMet + " · " + percent(notMet, total), "No cumplidos", ORANGE_BG, ORANGE_FG);
    kpiCell(
        kpis, noAnswer + " · " + percent(noAnswer, total), "Sin respuesta", SLATE_100, SLATE_700);
    doc.add(kpis);

    // Barra de distribución (cumplidos / no cumplidos / sin respuesta).
    List<float[]> parts = new ArrayList<>();
    List<Color> colors = new ArrayList<>();
    addPart(parts, colors, met, new Color(5, 150, 105));
    addPart(parts, colors, notMet, new Color(234, 88, 12));
    addPart(parts, colors, noAnswer, new Color(148, 163, 184));
    if (!parts.isEmpty()) {
      float[] widths = new float[parts.size()];
      for (int i = 0; i < parts.size(); i++) {
        widths[i] = parts.get(i)[0];
      }
      PdfPTable bar = new PdfPTable(widths);
      bar.setWidthPercentage(100);
      bar.setSpacingAfter(8);
      for (Color c : colors) {
        PdfPCell cell = new PdfPCell(new Phrase(" ", font(FontFactory.HELVETICA, 3, c)));
        cell.setFixedHeight(7);
        cell.setBackgroundColor(c);
        cell.setBorder(Rectangle.NO_BORDER);
        bar.addCell(cell);
      }
      doc.add(bar);
    }

    long validated =
        data.controls().stream()
            .filter(
                r ->
                    r.compliance() == Compliance.MET
                        && r.verdict() == AuditControlVerdict.Verdict.VALIDATED)
            .count();
    long openObs = data.observations().stream().filter(TechnicalReportService::isOpen).count();
    PdfPTable second = new PdfPTable(3);
    second.setWidthPercentage(100);
    second.setSpacingAfter(8);
    statCell(second, "Validados por el auditor", validated + " de " + met + " cumplidos");
    statCell(
        second,
        "Observaciones del auditor",
        data.observations().size() + " · " + openObs + (openObs == 1 ? " abierta" : " abiertas"));
    statCell(second, "Evidencias adjuntas", data.evidences().size() + " archivos");
    doc.add(second);

    doc.add(paragraph(readingText(data, met, total), 9.5f));
  }

  private static String readingText(Data data, long met, int total) {
    StringBuilder text =
        new StringBuilder("Lectura: la organización cumple el ")
            .append(percent(met, total))
            .append(" de los controles evaluados (")
            .append(met)
            .append(" de ")
            .append(total)
            .append(").");
    List<SubcategoryBlock> below =
        data.blocks().stream().filter(b -> b.current() < b.target()).toList();
    if (below.isEmpty()) {
      text.append(" Todas las subcategorías alcanzan su nivel objetivo.");
    } else {
      Map<String, Long> byFunction =
          below.stream()
              .collect(
                  Collectors.groupingBy(
                      SubcategoryBlock::function, LinkedHashMap::new, Collectors.counting()));
      Map.Entry<String, Long> worst =
          byFunction.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
      text.append(" ")
          .append(below.size())
          .append(" de ")
          .append(data.blocks().size())
          .append(" subcategorías quedan por debajo de su nivel objetivo; la función con más")
          .append(" brechas es «")
          .append(worst.getKey())
          .append("» (")
          .append(worst.getValue())
          .append(").");
    }
    long questioned =
        data.controls().stream()
            .filter(
                r ->
                    r.compliance() == Compliance.MET
                        && (r.verdict() == AuditControlVerdict.Verdict.NOT_VALIDATED
                            || r.verdict() == AuditControlVerdict.Verdict.NEEDS_MORE_EVIDENCE))
            .count();
    if (questioned > 0) {
      text.append(" Hay ")
          .append(questioned)
          .append(questioned == 1 ? " control marcado" : " controles marcados")
          .append(" como cumplido")
          .append(questioned == 1 ? "" : "s")
          .append(" que el auditor no validó o para ")
          .append(questioned == 1 ? "el que" : "los que")
          .append(" pidió más evidencia.");
    }
    return text.toString();
  }

  private void writeHowToRead(Document doc) throws DocumentException {
    sectionTitle(doc, "2. Cómo leer este reporte");
    // Un Paragraph vacío trae Helvetica (sin embeber) para su salto de línea: PDF/A lo rechaza.
    Paragraph p = new Paragraph("", font(FontFactory.HELVETICA, 9.5f, SLATE_700));
    p.add(new Chunk("Nivel acumulativo. ", font(FontFactory.HELVETICA_BOLD, 9.5f, SLATE_700)));
    p.add(
        new Chunk(
            "Una subcategoría alcanza el nivel N solo si cumple todos sus controles de nivel 1 a"
                + " N. Un control sin respuesta cuenta como no cumplido. Por eso un único control"
                + " pendiente de nivel 1 deja la subcategoría en nivel 0, aunque cumpla los de"
                + " niveles superiores.",
            font(FontFactory.HELVETICA, 9.5f, SLATE_700)));
    p.setLeading(13);
    p.setSpacingAfter(8);
    doc.add(p);

    PdfPTable legend = new PdfPTable(3);
    legend.setWidthPercentage(100);
    legendCell(
        legend,
        "CUMPLIMIENTO (evaluador)",
        "Cumple · control implementado\nNo cumple · no implementado\nSin respuesta · no respondido");
    legendCell(
        legend,
        "VEREDICTO (auditor)",
        "Validado · la evidencia lo respalda\nNo validado · no se sostiene\n"
            + "Más evidencia · respaldo insuficiente");
    legendCell(
        legend,
        "OBSERVACIONES (auditor)",
        "No conformidad · incumple el marco\nObservación · desvío a corregir\n"
            + "Recomendación · mejora sugerida");
    doc.add(legend);
  }

  private void writeFunctionMaturity(Document doc, Data data) throws DocumentException {
    sectionTitle(doc, "3. Madurez por función");
    PdfPTable table = new PdfPTable(new float[] {3f, 1.3f, 1.3f, 1.4f, 1.4f});
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    header(
        table,
        "Función",
        "Subcategorías",
        "En objetivo",
        "Nivel actual (prom.)",
        "Objetivo (prom.)");
    Map<String, List<SubcategoryBlock>> byFunction = new LinkedHashMap<>();
    for (SubcategoryBlock b : data.blocks()) {
      byFunction.computeIfAbsent(b.function(), k -> new ArrayList<>()).add(b);
    }
    int i = 0;
    for (Map.Entry<String, List<SubcategoryBlock>> e : byFunction.entrySet()) {
      Color bg = i++ % 2 == 0 ? Color.WHITE : SLATE_50;
      List<SubcategoryBlock> list = e.getValue();
      long atTarget = list.stream().filter(b -> b.current() >= b.target()).count();
      body(table, e.getKey(), bg, true);
      body(table, String.valueOf(list.size()), bg, false);
      body(table, String.valueOf(atTarget), bg, false);
      body(
          table,
          oneDecimal(list.stream().mapToInt(SubcategoryBlock::current).average()),
          bg,
          false);
      body(
          table, oneDecimal(list.stream().mapToInt(SubcategoryBlock::target).average()), bg, false);
    }
    if (byFunction.isEmpty()) {
      PdfPCell empty = new PdfPCell(new Phrase("Sin resultados de madurez.", italic(9)));
      empty.setColspan(5);
      empty.setPadding(6);
      table.addCell(empty);
    }
    doc.add(table);
  }

  /**
   * «Madurez por categoría»: el mismo radar y la misma tabla de siglas que la pestaña «Tablas y
   * promedios» del detalle de la evaluación. Va a continuación de la tabla por función y no cambia
   * la numeración de las secciones.
   */
  private void writeCategoryMaturity(Document doc, PdfWriter writer, Data data)
      throws DocumentException, IOException {
    List<CategoryMaturityChart.Point> points = categoryPoints(data);
    Paragraph heading =
        spaced(
            new Paragraph("Madurez por categoría", font(FontFactory.HELVETICA_BOLD, 11, SLATE_900)),
            14,
            3);
    if (points.isEmpty()) {
      doc.add(heading);
      doc.add(new Paragraph("Sin resultados de madurez.", italic(9)));
      return;
    }
    Paragraph note =
        paragraph(
            "Nivel actual promedio de las subcategorías de cada categoría, en escala de 0 a 4. En"
                + " el gráfico cada categoría se identifica por su sigla.",
            9);

    // Título, nota y gráfico forman un solo bloque: si no entran en lo que queda de la hoja pasan
    // juntos a la siguiente. Una imagen suelta que no entra queda «en espera» en iText y la tabla
    // que viene después se imprime primero, por delante del gráfico.
    PdfPTable block = new PdfPTable(1);
    block.setWidthPercentage(100);
    block.setKeepTogether(true);
    block.setSpacingAfter(8);
    PdfPCell cell = new PdfPCell();
    cell.setBorder(Rectangle.NO_BORDER);
    cell.setPadding(0);
    cell.addElement(heading);
    cell.addElement(note);
    if (CategoryMaturityChart.canRender(points)) {
      cell.addElement(CategoryMaturityChart.render(writer, points, doc.right() - doc.left()));
    }
    block.addCell(cell);
    doc.add(block);

    PdfPTable table = new PdfPTable(new float[] {1.1f, 3.4f, 2.6f, 1.3f});
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    header(table, "Sigla", "Categoría", "Función", "Nivel actual (prom.)");
    int i = 0;
    for (CategoryMaturityChart.Point p : points) {
      Color bg = i++ % 2 == 0 ? Color.WHITE : SLATE_50;
      body(table, p.code() != null && !p.code().isBlank() ? p.code() : "—", bg, true);
      body(table, p.name(), bg, false);
      body(table, p.functionName(), bg, false);
      body(table, String.format(java.util.Locale.ROOT, "%.1f", p.level()), bg, false);
    }
    doc.add(table);
  }

  /** Una categoría por cada par función/categoría, en el orden del catálogo, con su media. */
  private static List<CategoryMaturityChart.Point> categoryPoints(Data data) {
    Map<String, List<SubcategoryBlock>> byCategory = new LinkedHashMap<>();
    for (SubcategoryBlock b : data.blocks()) {
      byCategory
          .computeIfAbsent(b.function() + " › " + b.category(), k -> new ArrayList<>())
          .add(b);
    }
    List<CategoryMaturityChart.Point> points = new ArrayList<>();
    for (List<SubcategoryBlock> list : byCategory.values()) {
      SubcategoryBlock first = list.get(0);
      points.add(
          new CategoryMaturityChart.Point(
              first.function(),
              first.categoryCode(),
              first.category(),
              list.stream().mapToInt(SubcategoryBlock::current).average().orElse(0)));
    }
    return points;
  }

  // =====================================================================================
  // Detalle por subcategoría
  // =====================================================================================

  private void writeDetail(Document doc, Data data) throws DocumentException {
    doc.newPage();
    sectionTitle(doc, "4. Detalle por subcategoría");
    doc.add(
        paragraph(
            "Cada subcategoría muestra su nivel, la explicación de por qué está en ese nivel, sus"
                + " controles con el cumplimiento declarado y el veredicto del auditor, y las"
                + " observaciones registradas durante la auditoría. Las evidencias se citan por su"
                + " referencia (E-nn) y se detallan en el Anexo 5.",
            9));
    String function = null;
    String category = null;
    for (SubcategoryBlock b : data.blocks()) {
      if (!b.function().equals(function)) {
        function = b.function();
        category = null;
        Paragraph f =
            new Paragraph("Función " + function, font(FontFactory.HELVETICA_BOLD, 12, BRAND));
        f.setSpacingBefore(12);
        doc.add(f);
      }
      if (!b.category().equals(category)) {
        category = b.category();
        Paragraph c =
            new Paragraph(
                "Categoría " + category, font(FontFactory.HELVETICA_BOLD, 9.5f, SLATE_500));
        c.setSpacingBefore(4);
        doc.add(c);
      }
      writeBlock(doc, b);
    }
  }

  private void writeBlock(Document doc, SubcategoryBlock b) throws DocumentException {
    Paragraph title = new Paragraph(b.title(), font(FontFactory.HELVETICA_BOLD, 11.5f, SLATE_900));
    title.setSpacingBefore(8);
    title.setSpacingAfter(5);
    doc.add(title);

    // Niveles + explicación.
    PdfPTable levels = new PdfPTable(new float[] {1.25f, 3f});
    levels.setWidthPercentage(100);
    levels.setSpacingAfter(6);
    PdfPTable numbers = new PdfPTable(3);
    numbers.setWidthPercentage(100);
    int gap = Math.max(0, b.target() - b.current());
    levelNumber(numbers, "ACTUAL", b.current(), gap > 0 ? ORANGE_FG : GREEN_FG);
    levelNumber(numbers, "OBJETIVO", b.target(), SLATE_900);
    levelNumber(numbers, "BRECHA", gap, gap > 0 ? RED_FG : GREEN_FG);
    PdfPCell numbersCell = new PdfPCell(numbers);
    numbersCell.setBorder(Rectangle.NO_BORDER);
    numbersCell.setBackgroundColor(SLATE_50);
    numbersCell.setPadding(8);
    levels.addCell(numbersCell);
    PdfPCell why = new PdfPCell();
    why.setBorder(Rectangle.NO_BORDER);
    why.setBackgroundColor(SLATE_50);
    why.setPadding(8);
    why.addElement(
        new Paragraph(
            "Por qué está en nivel " + b.current(),
            font(FontFactory.HELVETICA_BOLD, 9, SLATE_700)));
    Paragraph whyText = new Paragraph(whyText(b), font(FontFactory.HELVETICA, 9, SLATE_700));
    whyText.setLeading(12);
    why.addElement(whyText);
    String next = nextStepText(b);
    if (next != null) {
      Paragraph step = new Paragraph(next, font(FontFactory.HELVETICA_BOLD, 9, BRAND));
      step.setSpacingBefore(3);
      why.addElement(step);
    }
    levels.addCell(why);
    doc.add(levels);

    // Controles.
    PdfPTable table = new PdfPTable(new float[] {1.05f, 4.7f, 0.75f, 1.55f, 1.55f, 1.3f});
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    header(
        table,
        "Código",
        "Control y comentario del evaluador",
        "Nivel",
        "Cumplimiento",
        "Veredicto",
        "Evidencias");
    int i = 0;
    for (ControlRow r : b.controls()) {
      Color bg =
          r.compliance() == Compliance.NOT_MET ? ORANGE_ROW : (i % 2 == 0 ? Color.WHITE : SLATE_50);
      i++;
      body(table, r.control().getCode(), bg, true);
      PdfPCell desc = new PdfPCell();
      styleBody(desc, bg);
      Paragraph d =
          new Paragraph(r.control().getDescription(), font(FontFactory.HELVETICA, 8.5f, SLATE_900));
      d.setLeading(11);
      desc.addElement(d);
      Paragraph comment =
          new Paragraph(
              r.comment() != null ? "«" + r.comment() + "»" : "Sin comentario del evaluador.",
              font(FontFactory.HELVETICA_OBLIQUE, 8, SLATE_500));
      comment.setLeading(11);
      desc.addElement(comment);
      table.addCell(desc);
      PdfPCell lvl = bodyCell(String.valueOf(r.control().getTargetLevel()), bg, false);
      lvl.setHorizontalAlignment(Element.ALIGN_CENTER);
      table.addCell(lvl);
      table.addCell(complianceCell(r.compliance(), bg));
      table.addCell(verdictCell(r.verdict(), bg));
      PdfPCell ev =
          bodyCell(
              r.evidenceRefs().isEmpty() ? "Ninguna" : String.join(", ", r.evidenceRefs()),
              bg,
              false);
      ev.setHorizontalAlignment(Element.ALIGN_CENTER);
      table.addCell(ev);
    }
    doc.add(table);

    long met = count(b.controls(), Compliance.MET);
    long notMet = count(b.controls(), Compliance.NOT_MET);
    long noAnswer = count(b.controls(), Compliance.NO_ANSWER);
    long validated =
        b.controls().stream()
            .filter(r -> r.verdict() == AuditControlVerdict.Verdict.VALIDATED)
            .count();
    Paragraph tally =
        new Paragraph(
            plural(b.controls().size(), "control", "controles")
                + " · "
                + plural(met, "cumplido", "cumplidos")
                + " · "
                + plural(notMet, "no cumplido", "no cumplidos")
                + " · "
                + noAnswer
                + " sin respuesta · "
                + plural(validated, "validado", "validados")
                + " por el auditor",
            font(FontFactory.HELVETICA, 8, SLATE_500));
    tally.setSpacingBefore(3);
    tally.setSpacingAfter(5);
    doc.add(tally);

    // Observaciones del auditor.
    Paragraph obsTitle =
        new Paragraph("Observaciones del auditor", font(FontFactory.HELVETICA_BOLD, 9.5f, BRAND));
    obsTitle.setSpacingAfter(3);
    doc.add(obsTitle);
    if (b.observations().isEmpty()) {
      Paragraph none = new Paragraph("Sin observaciones del auditor.", italic(8.5f));
      none.setSpacingAfter(6);
      doc.add(none);
      return;
    }
    for (AuditObservation o : b.observations()) {
      doc.add(observationBox(o));
    }
  }

  private PdfPTable observationBox(AuditObservation o) {
    Color bg;
    Color fg;
    switch (o.getType()) {
      case NON_CONFORMITY -> {
        bg = RED_BG;
        fg = RED_FG;
      }
      case RECOMMENDATION -> {
        bg = BLUE_BG;
        fg = BLUE_FG;
      }
      default -> {
        bg = AMBER_BG;
        fg = AMBER_FG;
      }
    }
    PdfPTable box = new PdfPTable(1);
    box.setWidthPercentage(100);
    box.setSpacingAfter(4);
    PdfPCell cell = new PdfPCell();
    cell.setBorderColor(bg);
    cell.setBorderWidth(1);
    cell.setPadding(7);
    Paragraph head = new Paragraph("", font(FontFactory.HELVETICA, 7.5f, SLATE_700));
    head.add(
        new Chunk(
            typeLabel(o.getType()).toUpperCase(), font(FontFactory.HELVETICA_BOLD, 7.5f, fg)));
    head.add(
        new Chunk(
            "   "
                + (o.getControl() != null ? o.getControl().getCode() : "Subcategoría")
                + " · "
                + observationStatusLabel(o.getStatus())
                + " · "
                + nameOf(o.getCreatedBy(), "Auditor")
                + (o.getCreatedAt() != null ? " · " + o.getCreatedAt().format(DATE) : ""),
            font(FontFactory.HELVETICA, 7.5f, SLATE_500)));
    cell.addElement(head);
    Paragraph text = new Paragraph(o.getDescription(), font(FontFactory.HELVETICA, 9, SLATE_700));
    text.setLeading(12);
    cell.addElement(text);
    box.addCell(cell);
    return box;
  }

  // =====================================================================================
  // Anexos
  // =====================================================================================

  private void writeAnnexes(Document doc, Evaluation eval, Data data) throws DocumentException {
    doc.newPage();
    sectionTitle(doc, "5. Evidencias adjuntas");
    doc.add(
        paragraph(
            "Cada archivo se identifica por su huella SHA-256, calculada al subirlo: permite"
                + " comprobar que el documento presentado es el mismo que se evaluó.",
            9));
    if (data.evidences().isEmpty()) {
      doc.add(new Paragraph("La evaluación no tiene evidencias adjuntas.", italic(9)));
    } else {
      PdfPTable table = new PdfPTable(new float[] {0.8f, 3.6f, 1.1f, 2f, 1.9f, 1.2f});
      table.setWidthPercentage(100);
      table.setHeaderRows(1);
      header(table, "Ref.", "Archivo", "Control", "SHA-256", "Subido por", "Fecha");
      int i = 0;
      for (EvidenceRow row : data.evidences()) {
        Color bg = i++ % 2 == 0 ? Color.WHITE : SLATE_50;
        Evidence e = row.evidence();
        body(table, row.ref(), bg, true);
        body(
            table,
            e.getFileName() + (e.getControl() == null ? " (general, sin control)" : ""),
            bg,
            false);
        body(table, e.getControl() != null ? e.getControl().getCode() : "—", bg, false);
        body(table, shortHash(e.getFileHash()), bg, false);
        body(table, nameOf(e.getUploadedBy(), "—"), bg, false);
        body(table, e.getUploadedAt() != null ? e.getUploadedAt().format(DATE) : "—", bg, false);
      }
      doc.add(table);
    }

    sectionTitle(doc, "6. Pendientes que frenan el nivel");
    doc.add(
        paragraph(
            "Controles no cumplidos o sin respuesta que, por el modelo acumulativo, impiden que su"
                + " subcategoría suba de nivel. Ordenados por el nivel que desbloquean.",
            9));
    record Pending(String code, String detail, int unlocks, boolean fromZero) {}
    List<Pending> pending = new ArrayList<>();
    for (SubcategoryBlock b : data.blocks()) {
      if (b.current() >= b.target()) {
        continue;
      }
      for (ControlRow r : blocking(b)) {
        boolean openNc =
            b.observations().stream()
                .anyMatch(
                    o ->
                        o.getType() == AuditObservation.ObservationType.NON_CONFORMITY
                            && isOpen(o)
                            && o.getControl() != null
                            && o.getControl().getId().equals(r.control().getId()));
        String reason = r.compliance() == Compliance.NO_ANSWER ? "sin respuesta" : "no cumplido";
        pending.add(
            new Pending(
                r.control().getCode(),
                b.title() + " · " + reason + (openNc ? " · no conformidad abierta" : ""),
                b.current() + 1,
                b.current() == 0));
      }
    }
    pending.sort(Comparator.comparingInt(Pending::unlocks).thenComparing(Pending::code));
    if (pending.isEmpty()) {
      doc.add(
          new Paragraph(
              "No hay pendientes: todas las subcategorías alcanzan su nivel objetivo.", italic(9)));
    } else {
      PdfPTable table = new PdfPTable(new float[] {1.2f, 6f, 1.8f});
      table.setWidthPercentage(100);
      table.setHeaderRows(1);
      header(table, "Control", "Subcategoría · motivo", "Desbloquea");
      int i = 0;
      for (Pending p : pending) {
        Color bg = i++ % 2 == 0 ? Color.WHITE : SLATE_50;
        body(table, p.code(), bg, true);
        body(table, p.detail(), bg, false);
        PdfPCell unlock =
            new PdfPCell(
                new Phrase(
                    "Nivel " + p.unlocks() + (p.fromZero() ? " (hoy en 0)" : ""),
                    font(FontFactory.HELVETICA_BOLD, 8.5f, p.fromZero() ? RED_FG : ORANGE_FG)));
        styleBody(unlock, bg);
        table.addCell(unlock);
      }
      doc.add(table);
    }

    sectionTitle(doc, "7. Trazabilidad y certificación");
    Paragraph traceIntro =
        new Paragraph(
            "Recorrido de la evaluación según la bitácora: cada cambio de estado con quién lo hizo"
                + " y cuándo. El ciclo cuenta las veces que se envió a auditoría.",
            font(FontFactory.HELVETICA, 8.5f, SLATE_700));
    traceIntro.setSpacingAfter(6);
    doc.add(traceIntro);
    Paragraph cycleTitle =
        new Paragraph("CICLO DE LA EVALUACIÓN", font(FontFactory.HELVETICA_BOLD, 7.5f, SLATE_500));
    cycleTitle.setSpacingAfter(3);
    doc.add(cycleTitle);
    if (data.trail().isEmpty()) {
      Paragraph none = new Paragraph("Sin cambios de estado registrados.", italic(8.5f));
      none.setSpacingAfter(8);
      doc.add(none);
    } else {
      PdfPTable trail = new PdfPTable(new float[] {19, 8, 47, 26});
      trail.setWidthPercentage(100);
      trail.setHeaderRows(1);
      header(trail, "Fecha y hora", "Ciclo", "Cambio", "Quién");
      // Toda evaluación nace en borrador: es el «desde» del primer cambio registrado.
      String previous = ReportService.statusLabel(Evaluation.Status.DRAFT);
      for (EvaluationAuditCycleEventDto event : data.trail()) {
        boolean isSeal = "CERTIFY".equals(event.action());
        Color bg = isSeal ? GREEN_BG : Color.WHITE;
        body(trail, event.occurredAt() != null ? dateTime(event.occurredAt()) : "—", bg, false);
        body(trail, event.cycleNumber() > 0 ? String.valueOf(event.cycleNumber()) : "—", bg, false);
        String current = trailLabel(event);
        if (event.status() != null) {
          // «›» y no «→»: la Helvetica estándar del PDF (WinAnsi) no tiene la flecha.
          body(trail, previous + " › " + current, bg, false);
          previous = current;
        } else {
          body(trail, current, bg, true);
        }
        body(trail, event.performedByName() != null ? event.performedByName() : "—", bg, false);
      }
      trail.setSpacingAfter(8);
      doc.add(trail);
    }

    PdfPTable trace = new PdfPTable(1);
    trace.setWidthPercentage(100);
    PdfPCell seal = new PdfPCell();
    seal.setBorderColor(GREEN_BG);
    seal.setPadding(8);
    seal.addElement(
        new Paragraph("SELLO DE CERTIFICACIÓN", font(FontFactory.HELVETICA_BOLD, 7.5f, GREEN_FG)));
    seal.addElement(
        new Paragraph(
            eval.getCertificationSeal() != null ? eval.getCertificationSeal() : "Sin sello",
            font(FontFactory.COURIER, 7.5f, SLATE_900)));
    Paragraph sealText =
        new Paragraph(
            "Firma de quién aprobó, cuándo, la organización, la evaluación y la madurez global. Si"
                + " alguno de esos datos se altera después de aprobar, el sello deja de coincidir.",
            font(FontFactory.HELVETICA, 8, SLATE_700));
    sealText.setLeading(11);
    sealText.setSpacingBefore(3);
    seal.addElement(sealText);
    trace.addCell(seal);
    trace.setSpacingAfter(8);
    doc.add(trace);

    String generatedBy = currentUser.currentUser().map(u -> nameOf(u, u.getEmail())).orElse("—");
    doc.add(
        paragraph(
            "Generado el "
                + OffsetDateTime.now().format(DATE_TIME)
                + " por "
                + generatedBy
                + " desde PRISMA. Este reporte refleja el estado de la evaluación al momento de su"
                + " aprobación y no la modifica.",
            8.5f));
  }

  // =====================================================================================
  // Helpers de maquetado
  // =====================================================================================

  // PDF/A-1b (RNF-12): las fuentes tienen que ir embebidas, así que no se usan las Helvetica
  // estándar de FontFactory. El nombre sigue siendo el de FontFactory para no tocar los ~60
  // llamados:
  // «Bold» elige la negrita embebida y «Oblique» simula la cursiva (la familia embebida no la
  // trae).
  private static Font font(String name, float size, Color color) {
    Font font = PdfA.font(name.contains("Bold"), size, color);
    if (name.contains("Oblique")) {
      font.setStyle(Font.ITALIC);
    }
    return font;
  }

  private static Font italic(float size) {
    return font(FontFactory.HELVETICA_OBLIQUE, size, SLATE_500);
  }

  private static Paragraph paragraph(String text, float size) {
    Paragraph p = new Paragraph(text, font(FontFactory.HELVETICA, size, SLATE_700));
    p.setLeading(size + 3.5f);
    p.setSpacingAfter(6);
    return p;
  }

  private static Paragraph spaced(Paragraph p, float before, float after) {
    p.setSpacingBefore(before);
    p.setSpacingAfter(after);
    return p;
  }

  private static void sectionTitle(Document doc, String text) throws DocumentException {
    Paragraph p = new Paragraph(text, font(FontFactory.HELVETICA_BOLD, 12.5f, BRAND));
    p.setSpacingBefore(14);
    doc.add(p);
    // El Chunk de una línea trae la fuente por defecto (Helvetica sin embeber), que PDF/A rechaza:
    // aunque solo dibuje una línea, se le asigna la fuente embebida.
    Chunk rule = new Chunk(new LineSeparator(1.5f, 100, BRAND, Element.ALIGN_LEFT, -3));
    rule.setFont(PdfA.font(false, 8, Color.BLACK));
    Paragraph line = new Paragraph(rule);
    line.setSpacingAfter(6);
    doc.add(line);
  }

  private static void infoCell(PdfPTable table, String label, String value) {
    PdfPCell cell = new PdfPCell();
    cell.setBorderColor(SLATE_200);
    cell.setPadding(6);
    cell.addElement(new Paragraph(label, font(FontFactory.HELVETICA_BOLD, 6.5f, SLATE_500)));
    cell.addElement(new Paragraph(value, font(FontFactory.HELVETICA_BOLD, 9, SLATE_900)));
    table.addCell(cell);
  }

  private static void kpiCell(PdfPTable table, String value, String label, Color bg, Color fg) {
    PdfPCell cell = new PdfPCell();
    cell.setBackgroundColor(bg);
    cell.setBorderColor(Color.WHITE);
    cell.setBorderWidth(2);
    cell.setPadding(7);
    cell.addElement(new Paragraph(value, font(FontFactory.HELVETICA_BOLD, 14, fg)));
    cell.addElement(new Paragraph(label, font(FontFactory.HELVETICA, 8, fg)));
    table.addCell(cell);
  }

  private static void statCell(PdfPTable table, String label, String value) {
    PdfPCell cell = new PdfPCell();
    cell.setBorderColor(SLATE_200);
    cell.setPadding(6);
    cell.addElement(new Paragraph(label, font(FontFactory.HELVETICA, 8, SLATE_500)));
    cell.addElement(new Paragraph(value, font(FontFactory.HELVETICA_BOLD, 10, SLATE_900)));
    table.addCell(cell);
  }

  private static void legendCell(PdfPTable table, String title, String lines) {
    PdfPCell cell = new PdfPCell();
    cell.setBorder(Rectangle.NO_BORDER);
    cell.setPaddingRight(6);
    cell.addElement(new Paragraph(title, font(FontFactory.HELVETICA_BOLD, 7.5f, SLATE_500)));
    Paragraph body = new Paragraph(lines, font(FontFactory.HELVETICA, 8.5f, SLATE_700));
    body.setLeading(12);
    cell.addElement(body);
    table.addCell(cell);
  }

  private static void levelNumber(PdfPTable table, String label, int value, Color color) {
    PdfPCell cell = new PdfPCell();
    cell.setBorder(Rectangle.NO_BORDER);
    cell.addElement(new Paragraph(label, font(FontFactory.HELVETICA_BOLD, 6.5f, SLATE_500)));
    cell.addElement(
        new Paragraph(String.valueOf(value), font(FontFactory.HELVETICA_BOLD, 17, color)));
    table.addCell(cell);
  }

  private static void header(PdfPTable table, String... labels) {
    for (String label : labels) {
      PdfPCell cell =
          new PdfPCell(new Phrase(label, font(FontFactory.HELVETICA_BOLD, 8, Color.WHITE)));
      cell.setBackgroundColor(BRAND);
      cell.setBorderColor(BRAND);
      cell.setPadding(5);
      table.addCell(cell);
    }
  }

  private static void body(PdfPTable table, String text, Color bg, boolean bold) {
    table.addCell(bodyCell(text, bg, bold));
  }

  private static PdfPCell bodyCell(String text, Color bg, boolean bold) {
    PdfPCell cell =
        new PdfPCell(
            new Phrase(
                text,
                font(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 8.5f, SLATE_900)));
    styleBody(cell, bg);
    return cell;
  }

  private static void styleBody(PdfPCell cell, Color bg) {
    cell.setBackgroundColor(bg);
    cell.setBorder(Rectangle.BOTTOM);
    cell.setBorderColor(SLATE_200);
    cell.setPadding(5);
  }

  private static PdfPCell complianceCell(Compliance compliance, Color rowBg) {
    String label;
    Color fg;
    switch (compliance) {
      case MET -> {
        label = "CUMPLE";
        fg = GREEN_FG;
      }
      case NOT_MET -> {
        label = "NO CUMPLE";
        fg = ORANGE_FG;
      }
      default -> {
        label = "SIN RESPUESTA";
        fg = SLATE_700;
      }
    }
    PdfPCell cell = new PdfPCell(new Phrase(label, font(FontFactory.HELVETICA_BOLD, 7.5f, fg)));
    styleBody(cell, rowBg);
    return cell;
  }

  private static PdfPCell verdictCell(AuditControlVerdict.Verdict verdict, Color rowBg) {
    String label;
    Color fg;
    if (verdict == null) {
      label = "Sin revisar";
      fg = SLATE_500;
    } else {
      switch (verdict) {
        case VALIDATED -> {
          label = "Validado";
          fg = GREEN_FG;
        }
        case NOT_VALIDATED -> {
          label = "No validado";
          fg = RED_FG;
        }
        default -> {
          label = "Más evidencia";
          fg = AMBER_FG;
        }
      }
    }
    PdfPCell cell = new PdfPCell(new Phrase(label, font(FontFactory.HELVETICA_BOLD, 8.5f, fg)));
    styleBody(cell, rowBg);
    return cell;
  }

  private static void addPart(List<float[]> parts, List<Color> colors, long value, Color color) {
    if (value > 0) {
      parts.add(new float[] {value});
      colors.add(color);
    }
  }

  private static long count(List<ControlRow> rows, Compliance compliance) {
    return rows.stream().filter(r -> r.compliance() == compliance).count();
  }

  private static String percent(long part, long total) {
    return total == 0 ? "0 %" : Math.round(part * 100.0 / total) + " %";
  }

  private static String oneDecimal(java.util.OptionalDouble value) {
    return value.isPresent()
        ? String.format(java.util.Locale.ROOT, "%.1f", value.getAsDouble())
        : "—";
  }

  private static boolean isOpen(AuditObservation o) {
    return o.getStatus() == AuditObservation.ObservationStatus.OPEN
        || o.getStatus() == AuditObservation.ObservationStatus.IN_PROGRESS;
  }

  private static String shortHash(String hash) {
    if (hash == null || hash.isBlank()) {
      return "—";
    }
    return hash.length() > 16 ? hash.substring(0, 16) + "…" : hash;
  }

  private static String nameOf(User user, String fallback) {
    if (user == null) {
      return fallback;
    }
    String name =
        ((user.getFirstName() == null ? "" : user.getFirstName())
                + " "
                + (user.getLastName() == null ? "" : user.getLastName()))
            .trim();
    return name.isEmpty() ? (user.getEmail() != null ? user.getEmail() : fallback) : name;
  }

  private static String typeLabel(AuditObservation.ObservationType type) {
    return switch (type) {
      case NON_CONFORMITY -> "No conformidad";
      case RECOMMENDATION -> "Recomendación";
      case OBSERVATION -> "Observación";
    };
  }

  private static String observationStatusLabel(AuditObservation.ObservationStatus status) {
    return switch (status) {
      case OPEN -> "Abierta";
      case IN_PROGRESS -> "En curso";
      case RESOLVED -> "Resuelta";
      case CLOSED -> "Cerrada";
    };
  }

  /** Fecha y hora en la zona del servidor (TZ): la base puede devolverla en UTC. */
  private static String dateTime(OffsetDateTime at) {
    return at.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DATE_TIME);
  }

  private static String trailLabel(EvaluationAuditCycleEventDto event) {
    if (event.status() != null) {
      try {
        return ReportService.statusLabel(Evaluation.Status.valueOf(event.status()));
      } catch (IllegalArgumentException e) {
        return event.status();
      }
    }
    return "CERTIFY".equals(event.action()) ? "Sello de certificación emitido" : event.action();
  }

  // =====================================================================================
  // Encabezado y pie de página («Página X de N»)
  // =====================================================================================

  private static final class PageDecorator extends PdfPageEventHelper {
    private final BaseFont regular;
    private final BaseFont bold;
    private final String headerRight;
    private final String footerLeft;
    private PdfTemplate total;

    PageDecorator(Evaluation eval) {
      try {
        this.regular = PdfA.regularBase();
        this.bold = PdfA.boldBase();
      } catch (RuntimeException e) {
        throw new IllegalStateException(e);
      }
      this.headerRight =
          eval.getOrganization().getName()
              + " · "
              + eval.getName()
              + " · MCU "
              + eval.getCatalogVersion()
              + (eval.getApprovedAt() != null
                  ? " · Aprobada " + eval.getApprovedAt().format(DATE)
                  : "");
      this.footerLeft = "PRISMA · Reporte técnico · " + eval.getName();
    }

    @Override
    public void onOpenDocument(PdfWriter writer, Document document) {
      total = writer.getDirectContent().createTemplate(30, 12);
    }

    @Override
    public void onEndPage(PdfWriter writer, Document document) {
      PdfContentByte cb = writer.getDirectContent();
      float left = document.left();
      float right = document.right();
      cb.saveState();
      if (writer.getPageNumber() > 1) {
        float y = document.top() + 24;
        cb.beginText();
        cb.setColorFill(BRAND);
        cb.setFontAndSize(bold, 9);
        cb.showTextAligned(PdfContentByte.ALIGN_LEFT, "PRISMA · Reporte técnico", left, y, 0);
        cb.setColorFill(SLATE_500);
        cb.setFontAndSize(regular, 7.5f);
        cb.showTextAligned(PdfContentByte.ALIGN_RIGHT, headerRight, right, y, 0);
        cb.endText();
        cb.setColorStroke(BRAND);
        cb.setLineWidth(1.2f);
        cb.moveTo(left, document.top() + 16);
        cb.lineTo(right, document.top() + 16);
        cb.stroke();
      }
      float y = document.bottom() - 26;
      cb.setColorStroke(SLATE_200);
      cb.setLineWidth(0.5f);
      cb.moveTo(left, document.bottom() - 14);
      cb.lineTo(right, document.bottom() - 14);
      cb.stroke();
      String page = "Página " + writer.getPageNumber() + " de ";
      float pageWidth = regular.getWidthPoint(page, 7.5f);
      float x = right - pageWidth - 12;
      cb.beginText();
      cb.setColorFill(SLATE_500);
      cb.setFontAndSize(regular, 7.5f);
      cb.showTextAligned(PdfContentByte.ALIGN_LEFT, footerLeft, left, y, 0);
      cb.showTextAligned(PdfContentByte.ALIGN_LEFT, page, x, y, 0);
      cb.endText();
      cb.addTemplate(total, x + pageWidth, y);
      cb.restoreState();
    }

    @Override
    public void onCloseDocument(PdfWriter writer, Document document) {
      total.beginText();
      total.setColorFill(SLATE_500);
      total.setFontAndSize(regular, 7.5f);
      total.setTextMatrix(0, 0);
      total.showText(String.valueOf(writer.getPageNumber() - 1));
      total.endText();
    }
  }
}
