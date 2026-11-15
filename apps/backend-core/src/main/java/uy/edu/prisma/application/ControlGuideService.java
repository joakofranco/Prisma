package uy.edu.prisma.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uy.edu.prisma.domain.entity.CatalogControl;
import uy.edu.prisma.domain.exception.InvalidRequestException;
import uy.edu.prisma.domain.exception.PayloadTooLargeException;
import uy.edu.prisma.domain.exception.ResourceNotFoundException;
import uy.edu.prisma.domain.repository.CatalogControlRepository;
import uy.edu.prisma.web.dto.Dto.ControlGuideImportResultDto;
import uy.edu.prisma.web.dto.Dto.ControlGuideItemDto;
import uy.edu.prisma.web.dto.Dto.ControlGuideSummaryDto;
import uy.edu.prisma.web.dto.Dto.SaveControlGuideDto;

@Service
@Transactional
public class ControlGuideService {

  static final long MAX_BYTES = 5L * 1024 * 1024;
  static final Set<String> MARKDOWN = Set.of("md", "markdown", "txt");
  static final Set<String> CSV = Set.of("csv");

  private static final String RESOURCE = "catalog_control_guide:";
  private static final List<String> CSV_HEADER =
      List.of("codigo", "nivel", "control", "interpretacion", "ejemplo", "evidencia");

  private final CatalogControlRepository controlRepo;
  private final AuditLogService auditLog;

  public ControlGuideService(CatalogControlRepository controlRepo, AuditLogService auditLog) {
    this.controlRepo = controlRepo;
    this.auditLog = auditLog;
  }

  @Transactional(readOnly = true)
  public ControlGuideSummaryDto summary(String version) {
    List<CatalogControl> controls = controlRepo.findByVersionOrdered(version);
    int withGuide = (int) controls.stream().filter(ControlGuideService::hasGuide).count();
    return new ControlGuideSummaryDto(controls.size(), withGuide);
  }

  @Transactional(readOnly = true)
  public List<ControlGuideItemDto> list(String version) {
    return controlRepo.findByVersionOrdered(version).stream()
        .map(ControlGuideService::toDto)
        .toList();
  }

  public ControlGuideItemDto save(UUID controlId, SaveControlGuideDto dto) {
    CatalogControl control = find(controlId);
    String interpretation = clean(dto.interpretation());
    String example = clean(dto.example());
    String evidence = clean(dto.evidence());
    boolean allBlank = interpretation == null && example == null && evidence == null;
    boolean allFilled = interpretation != null && example != null && evidence != null;
    if (!allBlank && !allFilled) {
      throw new InvalidRequestException(
          "Complete las tres secciones (interpretación, ejemplo y evidencia) o deje las tres"
              + " vacías para eliminar la guía.");
    }
    control.setGuideInterpretation(interpretation);
    control.setGuideExample(example);
    control.setGuideEvidence(evidence);
    controlRepo.save(control);
    auditLog.record(
        allBlank ? "DELETE_GUIDE" : "UPDATE_GUIDE",
        RESOURCE + control.getId(),
        "{\"code\":\"" + control.getCode() + "\"}");
    return toDto(control);
  }

  public int delete(List<UUID> controlIds) {
    List<CatalogControl> targets =
        controlRepo.findAllById(controlIds).stream().filter(ControlGuideService::hasGuide).toList();
    targets.forEach(
        control -> {
          control.setGuideInterpretation(null);
          control.setGuideExample(null);
          control.setGuideEvidence(null);
        });
    controlRepo.saveAll(targets);
    if (!targets.isEmpty()) {
      auditLog.record("DELETE_GUIDE", RESOURCE + "bulk", "{\"count\":" + targets.size() + "}");
    }
    return targets.size();
  }

  @Transactional(readOnly = true)
  public String exportCsv(String version) {
    List<CatalogControl> controls = controlRepo.findByVersionOrdered(version);
    if (controls.isEmpty()) {
      throw new ResourceNotFoundException("Versión del catálogo", version);
    }
    List<List<String>> rows = new ArrayList<>();
    rows.add(CSV_HEADER);
    for (CatalogControl control : controls) {
      rows.add(
          List.of(
              control.getCode(),
              String.valueOf(control.getTargetLevel()),
              control.getDescription(),
              nullToEmpty(control.getGuideInterpretation()),
              nullToEmpty(control.getGuideExample()),
              nullToEmpty(control.getGuideEvidence())));
    }
    return ControlGuideCsv.write(rows);
  }

  public ControlGuideImportResultDto importGuide(String version, MultipartFile file) {
    String extension = extension(file.getOriginalFilename());
    if (!MARKDOWN.contains(extension) && !CSV.contains(extension)) {
      throw new InvalidRequestException(
          "Formato no soportado. Se admite un archivo CSV o Markdown (.md).");
    }
    String text = read(file);
    List<CatalogControl> controls = controlRepo.findByVersionOrdered(version);
    if (controls.isEmpty()) {
      throw new ResourceNotFoundException("Versión del catálogo", version);
    }
    List<ControlGuideParser.Entry> entries =
        CSV.contains(extension) ? parseCsv(text) : ControlGuideParser.parse(text);
    if (entries.isEmpty()) {
      throw new InvalidRequestException(
          "No se encontraron controles en el archivo. Revise que tenga la columna «codigo» (CSV)"
              + " o los títulos «#### CÓDIGO (Nivel N)» (Markdown).");
    }
    Map<String, CatalogControl> byCode =
        controls.stream()
            .collect(Collectors.toMap(CatalogControl::getCode, Function.identity(), (a, b) -> a));

    List<String> incomplete = new ArrayList<>();
    Set<String> notInCatalog = new LinkedHashSet<>();
    List<CatalogControl> updated = new ArrayList<>();
    for (ControlGuideParser.Entry entry : entries) {
      CatalogControl control = byCode.get(entry.code());
      if (control == null) {
        notInCatalog.add(entry.code());
      } else if (!entry.isComplete()) {
        incomplete.add(entry.code());
      } else {
        control.setGuideInterpretation(entry.interpretation());
        control.setGuideExample(entry.example());
        control.setGuideEvidence(entry.evidence());
        updated.add(control);
      }
    }
    controlRepo.saveAll(updated);
    int withoutGuide = (int) controls.stream().filter(c -> !hasGuide(c)).count();
    auditLog.record(
        "IMPORT_GUIDE",
        RESOURCE + "version:" + version,
        "{\"version\":\""
            + version.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\",\"updated\":"
            + updated.size()
            + "}");
    return new ControlGuideImportResultDto(
        entries.size(), updated.size(), List.copyOf(notInCatalog), incomplete, withoutGuide);
  }

  private static List<ControlGuideParser.Entry> parseCsv(String text) {
    List<List<String>> rows = ControlGuideCsv.parse(text, ControlGuideCsv.detectDelimiter(text));
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> columns = new HashMap<>();
    List<String> header = rows.get(0);
    for (int i = 0; i < header.size(); i++) {
      columns.putIfAbsent(canonicalColumn(header.get(i)), i);
    }
    Integer code = columns.get("codigo");
    if (code == null) {
      return List.of();
    }
    List<ControlGuideParser.Entry> entries = new ArrayList<>();
    for (List<String> row : rows.subList(1, rows.size())) {
      String controlCode = cell(row, code);
      if (controlCode.isEmpty()) {
        continue;
      }
      entries.add(
          new ControlGuideParser.Entry(
              controlCode,
              cell(row, columns.get("interpretacion")),
              cell(row, columns.get("ejemplo")),
              cell(row, columns.get("evidencia"))));
    }
    return entries;
  }

  private static String canonicalColumn(String name) {
    String plain =
        Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .strip();
    if (plain.equals("code") || plain.equals("codigo") || plain.equals("codigo del control")) {
      return "codigo";
    }
    if (plain.startsWith("interpretacion")) {
      return "interpretacion";
    }
    if (plain.startsWith("ejemplo")) {
      return "ejemplo";
    }
    if (plain.startsWith("evidencia")) {
      return "evidencia";
    }
    return plain;
  }

  private static String cell(List<String> row, Integer index) {
    if (index == null || index >= row.size() || row.get(index) == null) {
      return "";
    }
    return row.get(index).strip();
  }

  private CatalogControl find(UUID controlId) {
    return controlRepo
        .findById(controlId)
        .orElseThrow(() -> new ResourceNotFoundException("Control", controlId));
  }

  private static ControlGuideItemDto toDto(CatalogControl control) {
    return new ControlGuideItemDto(
        control.getId(),
        control.getCode(),
        control.getDescription(),
        control.getTargetLevel(),
        control.getRequirement().getCode(),
        control.getGuideInterpretation(),
        control.getGuideExample(),
        control.getGuideEvidence());
  }

  private static boolean hasGuide(CatalogControl control) {
    return control.getGuideInterpretation() != null;
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String extension(String fileName) {
    String name = fileName == null ? "" : fileName;
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String read(MultipartFile file) {
    if (file.isEmpty()) {
      throw new InvalidRequestException("El archivo está vacío.");
    }
    if (file.getSize() > MAX_BYTES) {
      throw new PayloadTooLargeException("El archivo supera el máximo de 5 MB.");
    }
    try {
      return new String(file.getBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
