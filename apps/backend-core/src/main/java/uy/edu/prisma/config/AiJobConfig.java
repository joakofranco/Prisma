package uy.edu.prisma.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AiJobConfig {

  /**
   * Hilos que ejecutan los trabajos de IA de ESTA réplica. El tope global de concurrencia lo impone
   * la cola al reclamar (cuenta los que corren en todas las réplicas), así que acá nunca hay más
   * trabajos que {@code concurrency}; la cola chica es solo un margen.
   */
  @Bean(name = "aiJobExecutor")
  public ThreadPoolTaskExecutor aiJobExecutor(AiJobProperties props) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("ai-job-");
    executor.setCorePoolSize(props.concurrency());
    executor.setMaxPoolSize(props.concurrency());
    executor.setQueueCapacity(10);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    // Al apagar, espera un poco a los trabajos en curso; los que no terminen los recupera
    // AiJobQueue.recoverStale() cuando venza su reclamo.
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);
    return executor;
  }
}
