package io.github.moyalshoham.runway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The engine is plain Java: no Spring, no database, no web layer. */
class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.moyalshoham.runway");

    @Test
    void engineDependsOnNothingButTheJdk() {
        noClasses()
                .that()
                .resideInAPackage("..engine..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "jakarta..",
                        "com.fasterxml..",
                        "..runway.api..",
                        "..runway.service..",
                        "..runway.persistence..",
                        "..runway.importer..",
                        "..runway.sim..",
                        "..runway.backtest..")
                .check(classes);
    }

    @Test
    void controllersDoNotTalkToTheDatabaseDirectlyExceptForReads() {
        noClasses()
                .that()
                .resideInAPackage("..runway.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..")
                .check(classes);
    }
}
