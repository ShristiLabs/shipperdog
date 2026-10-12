package com.shipperdog.api.notification.internal.storage;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the notification module's audit-stream storage wiring (issue
 * #71, S14-05): one {@link Storage} client for the exported batches
 * and manifests -- deliberately its OWN bean, not the artifact or
 * feedback modules' (the modules' internals never import each
 * other, ArchUnit-enforced), over the same two targets: emulator
 * mode (a configured host, the compose GCS emulator) talks HTTP
 * without credentials; production mode falls through to the client's
 * defaults (application default credentials -- the writer identity
 * the customer grants bucket access to, docs/siem.md). One module,
 * one enablement site.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditStreamStorageProperties.class)
public class AuditStreamStorageConfig {

    @Bean
    Storage auditStreamBucketStorage(AuditStreamStorageProperties properties) {
        StorageOptions.Builder options = StorageOptions.newBuilder().setProjectId(properties.projectId());
        if (properties.host() != null && !properties.host().isBlank()) {
            options.setHost(properties.host());
            // The emulator accepts no credentials; a credential probe
            // against it is the classic local-setup failure.
            options.setCredentials(NoCredentials.getInstance());
        }
        return options.build().getService();
    }
}
