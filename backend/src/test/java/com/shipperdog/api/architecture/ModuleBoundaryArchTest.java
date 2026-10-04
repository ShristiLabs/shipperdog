package com.shipperdog.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Enforces the modular-monolith boundary (PRD section 22): a module may keep
 * implementation details inside its {@code .internal.} subpackage, and no
 * other module may import them. Cross-module calls must go through the
 * module's public API surface.
 */
class ModuleBoundaryArchTest {

    private static final String[] MODULES = {
        "identity",
        "organization",
        "tenant",
        "application",
        "version",
        "artifact",
        "tester",
        "authorization",
        "release",
        "workflow",
        "environment",
        "gate",
        "distribution",
        "feedback",
        "device",
        "analytics",
        "integration",
        "notification",
        "audit"
    };

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.shipperdog.api");

    @Test
    void moduleInternalsMustNotLeakAcrossModules() {
        for (ArchRule rule : rules()) {
            rule.check(CLASSES);
        }
    }

    @Test
    void allModulePackagesExist() {
        for (String module : MODULES) {
            assertThat(getClass().getClassLoader().getResource("com/shipperdog/api/" + module + "/"))
                    .as("bounded context package '%s' must exist (PRD section 22)", module)
                    .isNotNull();
        }
    }

    private static List<ArchRule> rules() {
        List<ArchRule> rules = new ArrayList<>();
        for (String module : MODULES) {
            rules.add(noClasses()
                    .that()
                    .resideOutsideOfPackage("..api." + module + "..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..api." + module + "..internal..")
                    .because("module '" + module + "' internals must not be imported from other modules"));
        }
        return rules;
    }
}
