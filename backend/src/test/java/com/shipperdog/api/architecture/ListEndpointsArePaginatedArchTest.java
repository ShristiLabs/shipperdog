package com.shipperdog.api.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Enforces the pagination convention structurally (issue #8): no
 * controller may answer a collection with a bare array — every list
 * endpoint returns the platform's {@code PageResponse} shape so callers
 * page one way everywhere and no query is unbounded.
 */
class ListEndpointsArePaginatedArchTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.shipperdog.api");

    @Test
    void controllersMustNotReturnBareCollections() {
        noMethods()
                .that()
                .areDeclaredInClassesThat()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .haveRawReturnType(List.class)
                .because("list endpoints must return the paginated PageResponse shape (issue #8), "
                        + "never a bare array")
                .check(CLASSES);
    }
}
