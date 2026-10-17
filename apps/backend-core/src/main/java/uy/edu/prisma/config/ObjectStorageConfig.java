package uy.edu.prisma.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(ObjectStorageConfig.StorageProperties.class)
public class ObjectStorageConfig {

  // "endpoint" es el nombre DNS interno (p.ej. http://rustfs:9000 en docker-compose) -- solo
  // resuelve entre contenedores, así que es el correcto para que EvidenceService hable con el
  // bucket (put/delete/head/create) y para firmar la URL que consume backend-ai del lado del
  // servidor. "publicEndpoint" es el host resoluble desde el navegador del usuario (p.ej.
  // http://localhost:9001 en dev local expuesto por docker-compose, o un dominio público en
  // producción) -- se usa SOLO para firmar la URL de descarga que termina en el frontend.
  @ConfigurationProperties(prefix = "prisma.storage")
  public record StorageProperties(
      String endpoint, String publicEndpoint, String accessKey, String secretKey, String bucket) {}

  // Región fija: la firma SigV4 queda 100% offline (el presigner nunca llama al servidor) y no
  // depende de que el endpoint público sea alcanzable desde dentro del contenedor. Es la región
  // por defecto de los servidores S3-compatibles y aparece en el querystring de la URL firmada
  // (X-Amz-Credential=.../us-east-1/s3/aws4_request).
  private static final Region REGION = Region.US_EAST_1;

  private static StaticCredentialsProvider credentials(StorageProperties p) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
  }

  // Los servidores S3-compatibles no resuelven "bucket.host": path-style es obligatorio.
  private static S3Configuration pathStyle() {
    return S3Configuration.builder().pathStyleAccessEnabled(true).build();
  }

  @Bean
  public S3Client s3Client(StorageProperties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(REGION)
        .credentialsProvider(credentials(properties))
        .serviceConfiguration(pathStyle())
        // Desde AWS SDK 2.30 se agregan checksums CRC por defecto, que algunos servidores
        // S3-compatibles no aceptan: solo se calculan cuando la operación los exige.
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  // Presigner INTERNO: URLs que baja backend-ai dentro de la red de contenedores.
  @Bean
  @Qualifier("internalPresigner")
  public S3Presigner internalPresigner(StorageProperties properties) {
    return presigner(properties, properties.endpoint());
  }

  // Presigner PÚBLICO: URLs que baja el navegador del usuario.
  @Bean
  @Qualifier("publicPresigner")
  public S3Presigner publicPresigner(StorageProperties properties) {
    return presigner(properties, properties.publicEndpoint());
  }

  private static S3Presigner presigner(StorageProperties properties, String endpoint) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(endpoint))
        .region(REGION)
        .credentialsProvider(credentials(properties))
        .serviceConfiguration(pathStyle())
        .build();
  }
}
