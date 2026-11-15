package uy.edu.prisma.config;

import jakarta.servlet.MultipartConfigElement;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Reglas de carga de evidencias (RF-EVI-02, RF-EVI-04), con el prefijo {@code prisma.evidence}. */
@Configuration
@EnableConfigurationProperties(EvidenceConfig.EvidenceProperties.class)
public class EvidenceConfig {

  // Margen para los otros campos del formulario (evaluationId, controlId, description) que viajan
  // en la misma petición multipart que el archivo.
  private static final long FORM_OVERHEAD_BYTES = DataSize.ofMegabytes(1).toBytes();

  /**
   * @param allowedExtensions extensiones permitidas, sin punto (por ejemplo {@code pdf}). Cada una
   *     tiene que existir en el catálogo de formatos de {@code EvidenceFileValidator}: una
   *     extensión desconocida hace fallar el arranque en lugar de ignorarse en silencio.
   * @param maxSize tamaño máximo de una evidencia (por defecto 100 MB, RF-EVI-04)
   */
  @ConfigurationProperties(prefix = "prisma.evidence")
  public record EvidenceProperties(
      List<String> allowedExtensions, @DefaultValue("100MB") DataSize maxSize) {

    // Copia inmutable: la lista que se expone no se puede modificar desde afuera.
    public EvidenceProperties {
      allowedExtensions =
          allowedExtensions == null
              ? List.of()
              : List.copyOf(allowedExtensions.stream().filter(Objects::nonNull).toList());
    }

    /** El límite en palabras para los mensajes: «100 MB», «512 KB» o «1500 bytes». */
    public String maxSizeDescription() {
      long bytes = maxSize.toBytes();
      long megabyte = DataSize.ofMegabytes(1).toBytes();
      long kilobyte = DataSize.ofKilobytes(1).toBytes();
      if (bytes % megabyte == 0) {
        return bytes / megabyte + " MB";
      }
      if (bytes % kilobyte == 0) {
        return bytes / kilobyte + " KB";
      }
      return bytes + " bytes";
    }
  }

  /**
   * Límite de Spring para la subida: el mismo valor que valida el servicio. Sin este bean regiría
   * el valor por defecto de Spring (1 MB por archivo) y ninguna evidencia real llegaría al
   * controlador. Si se sube el límite por encima de {@code client_max_body_size} de los Nginx
   * (infra/nginx/nginx.conf y apps/frontend/nginx.conf), hay que subir también ese valor.
   */
  @Bean
  public MultipartConfigElement multipartConfigElement(EvidenceProperties properties) {
    MultipartConfigFactory factory = new MultipartConfigFactory();
    factory.setMaxFileSize(properties.maxSize());
    factory.setMaxRequestSize(
        DataSize.ofBytes(properties.maxSize().toBytes() + FORM_OVERHEAD_BYTES));
    return factory.createMultipartConfig();
  }
}
