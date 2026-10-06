package com.shipperdog.api.artifact.internal.validation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The validation executor (issue #24, S5-05): a dedicated bounded pool
 * — never the common {@code @Async} executor, and deliberately NOT the
 * extraction pool — because the validation pipeline is scheduled FROM
 * the extraction job's settled phase: sharing one pool would let a
 * queued validation occupy the thread whose extraction result should
 * schedule it, and a saturated shared queue would deadlock the tiered
 * hand-off. Separate pools keep the two async tiers independently
 * backpressured; when THIS queue is full the scheduling call fails
 * fast and the artifact's validation document records
 * VALIDATION_QUEUE_FULL (the artifact stays VALIDATING, never a
 * silent stall).
 *
 * <p>Shutdown drains gracefully: an in-flight pipeline finishes within
 * the await window; queued-but-unstarted work is exactly what the
 * revalidation surface re-drives.
 */
@Configuration
@EnableConfigurationProperties(ArtifactValidationProperties.class)
class ArtifactValidationConfig {

    /** The validation pool; AbortPolicy so a full queue refuses loudly. */
    @Bean(name = "artifactValidationExecutor")
    ThreadPoolTaskExecutor artifactValidationExecutor(ArtifactValidationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("artifact-validation-");
        executor.setCorePoolSize(properties.concurrency());
        executor.setMaxPoolSize(properties.concurrency());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
