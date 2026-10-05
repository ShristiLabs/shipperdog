package com.shipperdog.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the generated OpenAPI contract deterministic (issue #8): springdoc
 * otherwise stamps the serving host's URL into {@code servers}, which in
 * tests is a random port — every build would then produce a spurious diff
 * against the committed baseline. The advertised server is the canonical
 * public API endpoint (Dwara fronts production traffic); operators can
 * override it per environment.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI shipperDogApiInfo(@Value("${shipperdog.openapi.version:0.1.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("ShipperDog API")
                        .description("Enterprise mobile-app beta distribution and release platform (PRD section 19).")
                        .version(version));
    }

    @Bean
    OpenApiCustomizer stableServersCustomizer(
            @Value("${shipperdog.openapi.server-url:https://api.shipperdog.dev}") String serverUrl) {
        return openApi -> openApi.servers(List.of(new Server().url(serverUrl).description("canonical API endpoint")));
    }
}
