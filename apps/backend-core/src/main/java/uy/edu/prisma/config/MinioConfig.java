package uy.edu.prisma.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioConfig.MinioProperties.class)
public class MinioConfig {

  // "endpoint" es el nombre DNS interno (p.ej. http://minio:9000 en docker-compose, o el Service
  // de Kubernetes) -- solo resuelve entre contenedores, así que es el correcto para que
  // EvidenceService hable con el bucket (put/remove/bucketExists). "publicEndpoint" es el host
  // resoluble desde el navegador del usuario (p.ej. http://localhost:9001 en dev local expuesto
  // por docker-compose, o un dominio público en producción) -- se usa SOLO para firmar la URL de
  // descarga que termina en el frontend. Antes se usaba el mismo cliente (con "endpoint") para
  // ambas cosas, y la URL de descarga quedaba con un host que ningún navegador puede resolver.
  @ConfigurationProperties(prefix = "prisma.minio")
  public record MinioProperties(
      String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket) {}

  // Region fija: sin ella, el SDK de MinIO resuelve la región del bucket llamando él mismo a
  // GetBucketLocation contra el endpoint configurado la primera vez que firma una URL -- para
  // minioPublicClient eso significa que el PROPIO backend-core (no el navegador del usuario)
  // necesita poder conectarse al endpoint público (p.ej. localhost:9001, que dentro del
  // contenedor no es el host sino el propio contenedor) solo para poder firmar, lo cual
  // reventaba con ConnectException aunque la URL resultante fuera perfectamente válida para
  // quien la recibe. Fijar la región de antemano evita ese round-trip: firmar queda offline. Toda
  // la stack corre MinIO con la región por defecto ("us-east-1"), la misma que ya aparece en el
  // querystring de cualquier URL firmada (X-Amz-Credential=.../us-east-1/s3/aws4_request).
  private static final String REGION = "us-east-1";

  @Bean
  public MinioClient minioClient(MinioProperties properties) {
    return MinioClient.builder()
        .endpoint(properties.endpoint())
        .region(REGION)
        .credentials(properties.accessKey(), properties.secretKey())
        .build();
  }

  @Bean
  @Qualifier("minioPublicClient")
  public MinioClient minioPublicClient(MinioProperties properties) {
    return MinioClient.builder()
        .endpoint(properties.publicEndpoint())
        .region(REGION)
        .credentials(properties.accessKey(), properties.secretKey())
        .build();
  }
}
