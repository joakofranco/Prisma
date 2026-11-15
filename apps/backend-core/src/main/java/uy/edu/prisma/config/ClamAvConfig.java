package uy.edu.prisma.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ClamAvConfig.ClamAvProperties.class)
public class ClamAvConfig {

  @ConfigurationProperties(prefix = "prisma.clamav")
  public record ClamAvProperties(
      @DefaultValue("localhost") String host,
      @DefaultValue("3310") int port,
      @DefaultValue("60s") Duration timeout) {}
}
