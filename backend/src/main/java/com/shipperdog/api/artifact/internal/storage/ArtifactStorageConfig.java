package com.shipperdog.api.artifact.internal.storage;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the artifact module's storage wiring (issue #20, S5-01,
 * ADR-0003): one {@link Storage} client for the whole application.
 * Emulator mode (a configured host, the compose GCS emulator) talks
 * HTTP without credentials; production mode falls through to the
 * client's defaults — application default credentials against real
 * GCS — so the SAME code path serves local, CI, and production, which
 * is the ADR's point.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ArtifactStorageProperties.class, ArtifactUploadProperties.class})
public class ArtifactStorageConfig {

    @Bean
    Storage artifactStorage(ArtifactStorageProperties properties) {
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
