package com.forgepilot;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

class ArchitectureRulesTest {

    private static final Set<String> ALLOWED_FEATURES = Set.of(
            "common", "auth", "project", "requirement", "scm", "knowledge", "ai", "review");
    private static final Set<String> FORBIDDEN_FEATURES = Set.of(
            "agent", "patch", "mq", "rag", "repo", "pullrequest", "context", "assistant", "finding");
    /** The only subpackages ARCHITECTURE.md 1.1 sanctions; everything else lives in the feature package itself. */
    private static final Set<String> ALLOWED_SUBPACKAGES = Set.of("scm.github", "scm.gitlab", "ai.openai");
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.forgepilot");

    @Test
    void topLevelPackagesAreExplicitlyAllowlisted() {
        classes().should(resideInAllowedTopLevelPackage()).check(PRODUCTION_CLASSES);
    }

    @Test
    void featureSlicesAreFreeOfCycles() {
        com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices()
                .matching("com.forgepilot.(*)..")
                .should().beFreeOfCycles()
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void scmCannotDependOnReview() {
        noClasses().that().resideInAnyPackage("com.forgepilot.scm..")
                .should().dependOnClassesThat().resideInAnyPackage("com.forgepilot.review..")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void crossFeatureRepositoriesAreNotInjectedDirectly() {
        classes().should(notDependOnRepositoryInAnotherFeature()).check(PRODUCTION_CLASSES);
    }

    @Test
    void controllersCannotReachCrossFeatureRepositories() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should(notDependOnRepositoryInAnotherFeature())
                .allowEmptyShould(true)
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void featureSubpackagesAreExplicitlyAllowlisted() {
        classes().should(resideInAnAllowedSubpackage()).check(PRODUCTION_CLASSES);
    }

    @Test
    void forbiddenAndCrossFeatureRulesAreNotTautologies() {
        JavaClasses fixtures = new ClassFileImporter().importPackages(
                "com.forgepilot.scm.fixture", "com.forgepilot.review.fixture", "com.forgepilot.agent.fixture");
        assertThatThrownBy(() -> classes().should(resideInAllowedTopLevelPackage()).check(fixtures))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> noClasses().that().resideInAnyPackage("com.forgepilot.scm..")
                .should().dependOnClassesThat().resideInAnyPackage("com.forgepilot.review..")
                .check(fixtures)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> classes().should(notDependOnRepositoryInAnotherFeature()).check(fixtures))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> classes().that().haveSimpleNameEndingWith("Controller")
                .should(notDependOnRepositoryInAnotherFeature()).check(fixtures))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void theTwoNewRulesAreNotTautologies() {
        JavaClasses fixtures = new ClassFileImporter().importPackages(
                "com.forgepilot.knowledge.fixture", "com.forgepilot.ai.fixture");

        // A class parked in an unlisted subpackage of an otherwise allowed feature.
        assertThatThrownBy(() -> classes().should(resideInAnAllowedSubpackage()).check(fixtures))
                .isInstanceOf(AssertionError.class);

        // A cross-feature Spring Data repository that the name suffix alone would miss.
        assertThatThrownBy(() -> classes().should(notDependOnRepositoryInAnotherFeature()).check(fixtures))
                .isInstanceOf(AssertionError.class);
    }

    private static ArchCondition<JavaClass> resideInAnAllowedSubpackage() {
        return new ArchCondition<>("reside in a feature package or an allowlisted subpackage") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String prefix = "com.forgepilot.";
                if (!item.getPackageName().startsWith(prefix)) {
                    return; // The top-level rule already owns this case.
                }
                String remainder = item.getPackageName().substring(prefix.length());
                boolean directlyInFeature = !remainder.isEmpty() && remainder.indexOf('.') < 0;
                if (remainder.isEmpty() || directlyInFeature || ALLOWED_SUBPACKAGES.contains(remainder)) {
                    return;
                }
                events.add(SimpleConditionEvent.violated(item,
                        item.getFullName() + " sits in unlisted subpackage " + remainder));
            }
        };
    }

    private static ArchCondition<JavaClass> resideInAllowedTopLevelPackage() {
        return new ArchCondition<>("reside in an allowed ForgePilot top-level package") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String[] parts = item.getPackageName().split("\\.");
                boolean root = parts.length == 2 && parts[0].equals("com") && parts[1].equals("forgepilot");
                boolean validFeature = parts.length >= 3 && parts[0].equals("com") && parts[1].equals("forgepilot")
                        && ALLOWED_FEATURES.contains(parts[2]) && !FORBIDDEN_FEATURES.contains(parts[2]);
                boolean valid = root || validFeature;
                if (!valid) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " is outside the allowed feature packages"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDependOnRepositoryInAnotherFeature() {
        return new ArchCondition<>("not depend directly on another feature's Repository") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String owner = featureOf(item.getPackageName());
                item.getDirectDependenciesFromSelf().stream()
                        .map(dependency -> dependency.getTargetClass())
                        .filter(ArchitectureRulesTest::isRepository)
                        .filter(target -> target.getPackageName().startsWith("com.forgepilot."))
                        .filter(target -> !featureOf(target.getPackageName()).equals(owner))
                        .forEach(target -> events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + " directly depends on " + target.getFullName())));
            }
        };
    }

    /**
     * Naming alone is not a boundary: a repository can be called anything. The
     * Spring Data type is the real signal, the suffix only catches the ones that
     * do not extend it.
     */
    private static boolean isRepository(JavaClass target) {
        return target.getSimpleName().endsWith("Repository")
                || target.isAssignableTo("org.springframework.data.repository.Repository");
    }

    private static String featureOf(String packageName) {
        String prefix = "com.forgepilot.";
        if (!packageName.startsWith(prefix)) {
            return "external";
        }
        String remainder = packageName.substring(prefix.length());
        int separator = remainder.indexOf('.');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }
}
