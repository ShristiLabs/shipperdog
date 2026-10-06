package com.shipperdog.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.shipperdog.api.authorization.AuthorizationService;
import com.shipperdog.api.authorization.DecisionGuarded;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The layered engine's structural gate (issue #30, acceptance
 * criterion 1): every tenant-scoped read and download surface marked
 * {@link DecisionGuarded} must route its authorization decision
 * through {@link AuthorizationService#decide} — and must not
 * hand-roll resource eligibility from other modules' internals or the
 * cross-tenant-by-design catalogs (those are the engine's inputs,
 * never a serving path's shortcuts).
 *
 * <p>The marker's own rules (see its javadoc): a guarded class depends
 * on {@code AuthorizationService}; it may use its OWN module's
 * repositories to serve the data the decision authorized (the catalog
 * listing's sanctioned visibility union IS the application layer's
 * set form), but no other module's {@code .internal.} packages and no
 * {@code @CrossTenantByDesign} types.
 */
class AuthorizationEngineArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.shipperdog.api");

    @Test
    void decisionGuardedSurfacesRouteThroughTheEngine() {
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith(DecisionGuarded.class)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(AuthorizationService.class)
                .because("a @DecisionGuarded surface must route its decision through "
                        + "AuthorizationService.decide (issue #30 AC1)");
        rule.check(CLASSES);
    }

    @Test
    void decisionGuardedSurfacesDoNotHandRollCrossTenantEligibility() {
        ArchRule rule = noClasses()
                .that()
                .areAnnotatedWith(DecisionGuarded.class)
                .should()
                .dependOnClassesThat()
                .areAnnotatedWith(CrossTenantByDesign.class)
                .because("cross-tenant-by-design carriers are the authorization engine's "
                        + "inputs; a serving path must not shortcut eligibility through them "
                        + "(issue #30 AC1)");
        rule.check(CLASSES);
    }

    @Test
    void decisionGuardedSurfacesStayOffOtherModulesInternals() {
        // A blanket '..internal..' ban would also forbid a guarded
        // class's OWN module internals (its own repository is its data
        // path, not authorization), so the rule is scoped per foreign
        // module — the ModuleBoundary test's own shape.
        List.of("identity", "organization", "tenant", "application", "version", "artifact", "tester", "audit")
                .forEach(module -> noClasses()
                        .that()
                        .areAnnotatedWith(DecisionGuarded.class)
                        .and()
                        .resideOutsideOfPackage("..api." + module + "..")
                        .should()
                        .dependOnClassesThat()
                        .resideInAPackage("..api." + module + "..internal..")
                        .because("a @DecisionGuarded surface of another module must not read '"
                                + module
                                + "' internals to decide eligibility (issue #30 AC1)")
                        .check(CLASSES));
    }
}
