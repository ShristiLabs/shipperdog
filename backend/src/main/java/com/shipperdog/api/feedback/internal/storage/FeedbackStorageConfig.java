package com.shipperdog.api.feedback.internal.storage;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the feedback module's storage wiring (issue #46, S11-01): one
 * {@link Storage} client for the module's attachments — deliberately
 * its OWN bean, not the artifact module's (the modules' internals
 * never import each other, ArchUnit-enforced), over the same two
 * targets the artifact store serves: emulator mode (a configured
 * host, the compose GCS emulator) talks HTTP without credentials;
 * production mode falls through to the client's defaults. One module,
 * one enablement site.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeedbackStorageProperties.class)
public class FeedbackStorageConfig {

    @Bean
    Storage feedbackAttachmentStorage(FeedbackStorageProperties properties) {
        StorageOptions.Builder options = StorageOptions.newBuilder().setProjectId(properties.projectId());
        if (properties.host() != null && !properties.host().isBlank()) {
            options.setHost(properties.host());
            // The emulator accepts no credentials; a credential probe
            // against it is the classic local-setup failure.
            options.setCredentials(com.google.cloud.NoCredentials.getInstance());
        }
        return options.build().getService();
    }
}
