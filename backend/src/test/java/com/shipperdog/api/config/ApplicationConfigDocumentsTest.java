package com.shipperdog.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Guards the multi-document layout of application.yml (issue #2 review):
 * server and management settings live in the default document so they
 * apply in every environment, and the dev profile document only appends
 * the seed location.
 */
class ApplicationConfigDocumentsTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void serverAndManagementSettingsApplyWithoutAnyProfile() {
        runner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());

            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(binder.bind("server.port", Integer.class).orElse(-1)).isEqualTo(8080);
            assertThat(binder.bind("server.shutdown", String.class).orElse("immediate"))
                    .isEqualTo("graceful");
            assertThat(binder.bind("management.endpoints.web.exposure.include", String.class)
                            .orElse(""))
                    .isEqualTo("health,info");
            assertThat(binder.bind("management.tracing.sampling.probability", Double.class)
                            .orElse(-1.0))
                    .as("baseline samples every request (issue #7)")
                    .isEqualTo(1.0);
            assertThat(binder.bind(
                                    "management.metrics.distribution.percentiles-histogram",
                                    Bindable.mapOf(String.class, Boolean.class))
                            .orElse(Map.of()))
                    .as("HTTP latency histogram is on by default (issue #7)")
                    .containsEntry("http.server.requests", true);
            assertThat(binder.bind("spring.flyway.locations", Bindable.listOf(String.class))
                            .orElse(List.of()))
                    .containsExactlyInAnyOrder("classpath:db/migration");
        });
    }

    @Test
    void serverPortEnvironmentOverrideIsHonoredWithoutAnyProfile() {
        runner.withPropertyValues("SERVER_PORT=9153").run(context -> {
            Binder binder = Binder.get(context.getEnvironment());

            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(binder.bind("server.port", Integer.class).orElse(-1)).isEqualTo(9153);
        });
    }

    @Test
    void devProfileOnlyAppendsTheSeedLocation() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            Binder binder = Binder.get(context.getEnvironment());

            assertThat(binder.bind("server.port", Integer.class).orElse(-1)).isEqualTo(8080);
            assertThat(binder.bind("spring.flyway.locations", Bindable.listOf(String.class))
                            .orElse(List.of()))
                    .containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/seed");
        });
    }
}
