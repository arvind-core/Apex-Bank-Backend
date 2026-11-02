package com.apexbank.architecture;

import java.util.Optional;

import com.apexbank.ApexBankBackendApplication;
import com.apexbank.shared.config.ApexBankProperties;
import com.apexbank.shared.config.TimeConfig;
import com.apexbank.shared.exception.GlobalExceptionHandler;
import com.apexbank.shared.logging.TraceIdFilter;
import com.apexbank.shared.security.SecurityConfig;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureTest {

	private static final String MODULES_PACKAGE_PREFIX = "com.apexbank.modules.";

	private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.apexbank");

	static final ArchRule PRODUCTION_CLASSES_MUST_RESIDE_IN_APPROVED_PACKAGES =
			classes()
					.that().resideInAPackage("com.apexbank..")
					.and().doNotHaveSimpleName("ApexBankBackendApplication")
					.should().resideInAnyPackage(
							"com.apexbank.shared..",
							"com.apexbank.modules.."
					)
					.because("all production classes except ApexBankBackendApplication must reside in com.apexbank.shared.. or com.apexbank.modules..");

	static final ArchRule SHARED_MUST_NOT_DEPEND_ON_MODULES =
			noClasses()
					.that().resideInAPackage("com.apexbank.shared..")
					.should().dependOnClassesThat()
					.resideInAPackage("com.apexbank.modules..")
					.because("shared cross-cutting code must not depend on individual feature modules");

	static final ArchRule MODULE_CLASSES_MUST_RESIDE_IN_APPROVED_LAYERS =
			classes()
					.that().resideInAPackage("com.apexbank.modules..")
					.should().resideInAnyPackage(
							"com.apexbank.modules.*.api..",
							"com.apexbank.modules.*.application..",
							"com.apexbank.modules.*.domain..",
							"com.apexbank.modules.*.infrastructure.."
					)
					.allowEmptyShould(true)
					.because("feature modules must organize code into api, application, domain, or infrastructure layers");

	static final ArchRule DOMAIN_MUST_NOT_DEPEND_ON_OUTER_LAYERS_OR_SPRING_WEB =
			noClasses()
					.that().resideInAPackage("com.apexbank.modules.*.domain..")
					.should().dependOnClassesThat()
					.resideInAnyPackage(
							"com.apexbank.modules.*.api..",
							"com.apexbank.modules.*.application..",
							"com.apexbank.modules.*.infrastructure..",
							"org.springframework.web..",
							"org.springframework.security.web..",
							"jakarta.servlet.."
					)
					.allowEmptyShould(true)
					.because("domain code must remain independent of API, application, infrastructure, and Spring Web layers");

	static final ArchRule API_AND_WEB_ENTRY_POINTS_MUST_NOT_ACCESS_PERSISTENCE_OR_INFRASTRUCTURE =
			noClasses()
					.that().resideInAPackage("com.apexbank.modules.*.api..")
					.or().areAnnotatedWith(RestController.class)
					.or().areAnnotatedWith(Controller.class)
					.or().areAnnotatedWith(RestControllerAdvice.class)
					.should().dependOnClassesThat()
					.resideInAnyPackage(
							"com.apexbank.modules.*.infrastructure..",
							"jakarta.persistence..",
							"org.springframework.data.jpa..",
							"org.springframework.data.repository..",
							"org.springframework.jdbc.."
					)
					.orShould().dependOnClassesThat()
					.areAnnotatedWith(org.springframework.stereotype.Repository.class)
					.orShould().dependOnClassesThat()
					.areAssignableTo(Repository.class)
					.because("controllers and API DTOs must delegate to application use cases rather than repositories or persistence infrastructure");

	static final ArchRule CONTROLLERS_AND_API_MUST_NOT_EXPOSE_JPA_ENTITIES =
			noClasses()
					.that().resideInAPackage("com.apexbank.modules.*.api..")
					.or().areAnnotatedWith(RestController.class)
					.or().areAnnotatedWith(Controller.class)
					.or().areAnnotatedWith(RestControllerAdvice.class)
					.should().dependOnClassesThat()
					.areAnnotatedWith(Entity.class)
					.orShould().beAnnotatedWith(Entity.class)
					.because("controllers and public API contracts must use dedicated DTOs rather than JPA @Entity classes");

	static final ArchRule APPLICATION_AND_INFRASTRUCTURE_MUST_NOT_DEPEND_ON_API_LAYER =
			noClasses()
					.that().resideInAnyPackage(
							"com.apexbank.modules.*.application..",
							"com.apexbank.modules.*.infrastructure.."
					)
					.should().dependOnClassesThat()
					.resideInAnyPackage(
							"com.apexbank.modules.*.api..",
							"org.springframework.web.bind.annotation.."
					)
					.allowEmptyShould(true)
					.because("application use cases and infrastructure implementations must not depend upward on the HTTP API layer");

	static final ArchRule MODULES_MUST_NOT_ACCESS_OTHER_MODULES_INTERNAL_OR_INFRASTRUCTURE_PACKAGES =
			classes()
					.that().resideInAPackage("com.apexbank.modules..")
					.should(notDependOnOtherModulesInternalOrInfrastructurePackages())
					.allowEmptyShould(true)
					.because("cross-module code must not directly import another module's api, infrastructure, or non-public internal implementation classes");

	static final ArchRule MODULES_MUST_BE_FREE_OF_CYCLES =
			slices()
					.matching("com.apexbank.modules.(*)..")
					.should().beFreeOfCycles()
					.allowEmptyShould(true)
					.because("feature modules must not form cyclic dependencies");

	@Test
	void productionImportContainsFoundationClassesAndExcludesTests() {
		assertThat(PRODUCTION_CLASSES.contain(ApexBankBackendApplication.class)).isTrue();
		assertThat(PRODUCTION_CLASSES.contain(ApexBankProperties.class)).isTrue();
		assertThat(PRODUCTION_CLASSES.contain(TimeConfig.class)).isTrue();
		assertThat(PRODUCTION_CLASSES.contain(GlobalExceptionHandler.class)).isTrue();
		assertThat(PRODUCTION_CLASSES.contain(TraceIdFilter.class)).isTrue();
		assertThat(PRODUCTION_CLASSES.contain(SecurityConfig.class)).isTrue();

		assertThat(PRODUCTION_CLASSES)
				.extracting(JavaClass::getName)
				.noneMatch(name -> name.contains("Test"))
				.doesNotContain(
						"com.apexbank.shared.exception.TraceAndExceptionHandlingTest$TestDiagnosticControllerConfig$TestDiagnosticController",
						"com.apexbank.shared.security.SecurityFoundationTest$TestSecurityEndpointsConfig$TestSecurityProbeController"
				);
	}

	@Test
	void productionClassesResideInApprovedTopLevelPackages() {
		PRODUCTION_CLASSES_MUST_RESIDE_IN_APPROVED_PACKAGES.check(PRODUCTION_CLASSES);
	}

	@Test
	void sharedCodeDoesNotDependOnFeatureModules() {
		SHARED_MUST_NOT_DEPEND_ON_MODULES.check(PRODUCTION_CLASSES);
	}

	@Test
	void moduleClassesResideInApprovedLayers() {
		MODULE_CLASSES_MUST_RESIDE_IN_APPROVED_LAYERS.check(PRODUCTION_CLASSES);
	}

	@Test
	void domainLayerRemainsPureAndIndependentOfSpringWeb() {
		DOMAIN_MUST_NOT_DEPEND_ON_OUTER_LAYERS_OR_SPRING_WEB.check(PRODUCTION_CLASSES);
	}

	@Test
	void apiAndWebEntryPointsDoNotAccessPersistenceOrInfrastructureDirectly() {
		API_AND_WEB_ENTRY_POINTS_MUST_NOT_ACCESS_PERSISTENCE_OR_INFRASTRUCTURE.check(PRODUCTION_CLASSES);
	}

	@Test
	void controllersAndApiDoNotExposeJpaEntities() {
		CONTROLLERS_AND_API_MUST_NOT_EXPOSE_JPA_ENTITIES.check(PRODUCTION_CLASSES);
	}

	@Test
	void applicationAndInfrastructureDoNotDependOnApiLayer() {
		APPLICATION_AND_INFRASTRUCTURE_MUST_NOT_DEPEND_ON_API_LAYER.check(PRODUCTION_CLASSES);
	}

	@Test
	void modulesDoNotAccessOtherModulesInternalOrInfrastructurePackages() {
		MODULES_MUST_NOT_ACCESS_OTHER_MODULES_INTERNAL_OR_INFRASTRUCTURE_PACKAGES.check(PRODUCTION_CLASSES);
		MODULES_MUST_BE_FREE_OF_CYCLES.check(PRODUCTION_CLASSES);
	}

	@Test
	void controlledViolatingFixturesAreRejectedByArchitectureRules() {
		JavaClasses unapprovedPackageFixture = new ClassFileImporter()
				.importClasses(UnapprovedTopLevelPackageFixture.class);

		assertThatThrownBy(() -> PRODUCTION_CLASSES_MUST_RESIDE_IN_APPROVED_PACKAGES.check(unapprovedPackageFixture))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining(UnapprovedTopLevelPackageFixture.class.getName());

		JavaClasses controllerWithRepositoryFixture = new ClassFileImporter()
				.importClasses(ControllerDependingOnRepositoryFixture.class, SampleRepositoryFixture.class);

		assertThatThrownBy(() -> API_AND_WEB_ENTRY_POINTS_MUST_NOT_ACCESS_PERSISTENCE_OR_INFRASTRUCTURE
				.check(controllerWithRepositoryFixture))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining(ControllerDependingOnRepositoryFixture.class.getName());

		JavaClasses controllerWithJdbcFixture = new ClassFileImporter()
				.importClasses(ControllerDependingOnJdbcFixture.class, JdbcClient.class);

		assertThatThrownBy(() -> API_AND_WEB_ENTRY_POINTS_MUST_NOT_ACCESS_PERSISTENCE_OR_INFRASTRUCTURE
				.check(controllerWithJdbcFixture))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining(ControllerDependingOnJdbcFixture.class.getName());
	}

	private static ArchCondition<JavaClass> notDependOnOtherModulesInternalOrInfrastructurePackages() {
		return new ArchCondition<>("not depend on another module's api, infrastructure, or non-public internal classes") {
			@Override
			public void check(JavaClass origin, ConditionEvents events) {
				Optional<String> originModuleOpt = extractModuleName(origin.getPackageName());
				if (originModuleOpt.isEmpty()) {
					return;
				}
				String originModule = originModuleOpt.get();

				for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
					JavaClass target = dependency.getTargetClass();
					Optional<String> targetModuleOpt = extractModuleName(target.getPackageName());
					if (targetModuleOpt.isEmpty()) {
						continue;
					}
					String targetModule = targetModuleOpt.get();
					if (originModule.equals(targetModule)) {
						continue;
					}

					String targetPackage = target.getPackageName();
					String moduleRoot = MODULES_PACKAGE_PREFIX + targetModule;
					boolean inForbiddenLayer = targetPackage.startsWith(moduleRoot + ".infrastructure")
							|| targetPackage.startsWith(moduleRoot + ".api")
							|| targetPackage.contains(".internal");
					boolean nonPublicTarget = !target.getModifiers().contains(JavaModifier.PUBLIC);

					if (inForbiddenLayer || nonPublicTarget) {
						String message = String.format(
								"Module '%s' class %s must not depend on module '%s' restricted class %s (%s)",
								originModule,
								origin.getName(),
								targetModule,
								target.getName(),
								dependency.getDescription()
						);
						events.add(SimpleConditionEvent.violated(origin, message));
					}
				}
			}
		};
	}

	private static Optional<String> extractModuleName(String packageName) {
		if (!packageName.startsWith(MODULES_PACKAGE_PREFIX)) {
			return Optional.empty();
		}
		String remainder = packageName.substring(MODULES_PACKAGE_PREFIX.length());
		if (remainder.isBlank()) {
			return Optional.empty();
		}
		int dotIndex = remainder.indexOf('.');
		String moduleName = dotIndex >= 0 ? remainder.substring(0, dotIndex) : remainder;
		return moduleName.isBlank() ? Optional.empty() : Optional.of(moduleName);
	}

	static class UnapprovedTopLevelPackageFixture {
	}

	@org.springframework.stereotype.Repository
	static class SampleRepositoryFixture {
	}

	@RestController
	static class ControllerDependingOnRepositoryFixture {

		private SampleRepositoryFixture repository;

		SampleRepositoryFixture repository() {
			return repository;
		}

	}

	@RestController
	static class ControllerDependingOnJdbcFixture {

		private JdbcClient jdbcClient;

		JdbcClient jdbcClient() {
			return jdbcClient;
		}

	}

}