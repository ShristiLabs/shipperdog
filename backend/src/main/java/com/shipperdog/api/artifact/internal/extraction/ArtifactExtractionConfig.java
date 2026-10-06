package com.shipperdog.api.artifact.internal.extraction;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The extraction executor (issue #21, S5-02): a dedicated bounded pool
 * — never the common {@code @Async} executor — because artifact
 * extraction is background work with its own backpressure contract:
 * when the queue is full the SCHEDULING call fails fast (the uploader
 * learns immediately, the artifact's error document records why) and
 * the upload path itself never blocks on parsing.
 *
 * <p>Shutdown drains gracefully: an in-flight extraction of a large
 * artifact finishes within the await window; queued-but-unstarted
 * work is what the EXTRACTION_QUEUE_FULL record would have shown had
 * the process died instead — S5-05's validation pipeline owns
 * closing that gap systematically.
 */
@Configuration
@EnableConfigurationProperties(ArtifactExtractionProperties.class)
class ArtifactExtractionConfig {

    /** The extraction pool; AbortPolicy so a full queue refuses loudly. */
    @Bean(name = "artifactExtractionExecutor")
    ThreadPoolTaskExecutor artifactExtractionExecutor(ArtifactExtractionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("artifact-extraction-");
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
