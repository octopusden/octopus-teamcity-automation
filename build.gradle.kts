import com.avast.gradle.dockercompose.ComposeExtension
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("com.gradleup.shadow") apply false
    id("io.spring.dependency-management") apply false
    id("org.jetbrains.kotlinx.kover")
    id("org.owasp.dependencycheck")
    id("com.avast.gradle.docker-compose")
    id("io.github.gradle-nexus.publish-plugin")
    id("org.octopusden.octopus-release-management")
    id("org.octopusden.octopus.oc-template")
    id("io.gitlab.arturbosch.detekt") apply false
    id("org.jlleitschuh.gradle.ktlint") apply false
    id("org.octopusden.octopus-quality")
    id("org.sonarqube")
}

octopusQuality {
    // Regression guard on what this repository publishes. It compares the publications the build
    // DECLARES, so the routed one is still listed: the release-time guard no longer sees it, and
    // this is what watches its shape.
    publication {
        enforceCentralPublications.set(true)
        centralPublications.set(
            setOf(
                ":cli|maven|org.octopusden.octopus.automation.teamcity:octopus-teamcity-automation|" +
                    "[jar, jar:all, jar:javadoc, jar:sources, zip:metarunners]",
                ":core|maven|org.octopusden.octopus.automation.teamcity:octopus-teamcity-automation-core|" +
                    "[jar, jar:javadoc, jar:sources]",
                // The service is a deployable shipped as a docker image, so only the two libraries
                // other services consume are listed.
                ":client|maven|org.octopusden.octopus.teamcity-automation-service:client|" +
                    "[jar, jar:javadoc, jar:sources]",
                ":common|maven|org.octopusden.octopus.teamcity-automation-service:common|" +
                    "[jar, jar:javadoc, jar:sources]",
            ),
        )
    }
    // Kover covers the service modules only (configured below, with its own thresholds); :core and
    // :cli are tested against real TeamCity servers and stay outside it.
    coverage {
        enabled.set(true)
    }
    // Enforce Kotlin static analysis (detekt + ktlint); current debt is absorbed by
    // detekt-baseline.xml / ktlint-baseline.xml so the gate stays green while enforcing.
    kotlin {
        failOnViolation.set(true)
    }
    // Coverage only: :core and :cli are tested against TeamCity servers in Docker or OKD, and ft
    // is run on its own.
    excludeProjects("ft", "core", "cli")
}

val cliProjects = listOf(project(":core"), project(":cli"))
val serviceProjects = listOf(project(":common"), project(":client"), project(":teamcity-automation-service"), project(":ft"))
val coveredServiceProjects = serviceProjects - project(":ft")

fun requiredIntProperty(propertyName: String): Int {
    val value = findProperty(propertyName)?.toString()?.trim()
        ?: throw IllegalStateException("Missing Gradle property '$propertyName' required for Kover verification")
    return value.toIntOrNull()
        ?: throw IllegalStateException("Gradle property '$propertyName' must be an integer, but was '$value'")
}

dependencies {
    coveredServiceProjects.forEach { add("kover", it) }
}

kover {
    reports {
        verify {
            rule("Line coverage") {
                bound {
                    minValue.set(requiredIntProperty("coverage.line.min"))
                    coverageUnits = CoverageUnit.LINE
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
            rule("Branch coverage") {
                bound {
                    minValue.set(requiredIntProperty("coverage.branch.min"))
                    coverageUnits = CoverageUnit.BRANCH
                    aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

dependencyCheck {
    failBuildOnCVSS = 11.0F
    formats = listOf("HTML", "JSON", "SARIF")
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
}

tasks.register("securityReport") {
    group = "verification"
    description = "Runs security checks in report-only mode."
    dependsOn(
        if (tasks.names.contains("dependencyCheckAggregate")) {
            "dependencyCheckAggregate"
        } else {
            "dependencyCheckAnalyze"
        },
    )
}

allprojects {
    group = "org.octopusden.octopus.automation.teamcity"
    description = "Octopus Teamcity Automation"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions {
            suppressWarnings = true
            jvmTarget = "21"
        }
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        withJavadocJar()
        withSourcesJar()
    }

    configure<PublishingExtension> {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/octopusden/octopus-teamcity-automation")
                credentials {
                    username = System.getenv("GITHUB_PACKAGES_USERNAME")
                    password = System.getenv("GITHUB_PACKAGES_TOKEN")
                }
            }
        }
    }
}

ext {
    System.getenv().let {
        set("signingRequired", it.containsKey("ORG_GRADLE_PROJECT_signingKey") && it.containsKey("ORG_GRADLE_PROJECT_signingPassword"))
        set("testPlatform", it.getOrDefault("TEST_PLATFORM", properties["test.platform"]))
        set("dockerRegistry", it.getOrDefault("DOCKER_REGISTRY", properties["docker.registry"]))
        set(
            "octopusGithubDockerRegistry",
            it.getOrDefault("OCTOPUS_GITHUB_DOCKER_REGISTRY", project.properties["octopus.github.docker.registry"]),
        )
        set("okdActiveDeadlineSeconds", it.getOrDefault("OKD_ACTIVE_DEADLINE_SECONDS", properties["okd.active-deadline-seconds"]))
        set("okdProject", it.getOrDefault("OKD_PROJECT", properties["okd.project"]))
        set("okdClusterDomain", it.getOrDefault("OKD_CLUSTER_DOMAIN", properties["okd.cluster-domain"]))
        set("okdWebConsoleUrl", (it.getOrDefault("OKD_WEB_CONSOLE_URL", properties["okd.web-console-url"]) as String).trimEnd('/'))
    }
}
val supportedTestPlatforms = listOf("docker", "okd")
if (project.ext["testPlatform"] !in supportedTestPlatforms) {
    throw IllegalArgumentException(
        "Test platform must be set to one of the following $supportedTestPlatforms. Start gradle build with -Ptest.platform=... or set env variable TEST_PLATFORM",
    )
}
val mandatoryProperties = mutableListOf("dockerRegistry", "octopusGithubDockerRegistry")
if (project.ext["testPlatform"] == "okd") {
    mandatoryProperties.add("okdActiveDeadlineSeconds")
    mandatoryProperties.add("okdProject")
    mandatoryProperties.add("okdClusterDomain")
}
val undefinedProperties = mandatoryProperties.filter { (project.ext[it] as String).isBlank() }
if (undefinedProperties.isNotEmpty()) {
    throw IllegalArgumentException(
        "Start gradle build with" +
            (if (undefinedProperties.contains("dockerRegistry")) " -Pdocker.registry=..." else "") +
            (if (undefinedProperties.contains("octopusGithubDockerRegistry")) " -Poctopus.github.docker.registry=..." else "") +
            (if (undefinedProperties.contains("okdActiveDeadlineSeconds")) " -Pokd.active-deadline-seconds=..." else "") +
            (if (undefinedProperties.contains("okdProject")) " -Pokd.project=..." else "") +
            (if (undefinedProperties.contains("okdClusterDomain")) " -Pokd.cluster-domain=..." else "") +
            " or set env variable(s):" +
            (if (undefinedProperties.contains("dockerRegistry")) " DOCKER_REGISTRY" else "") +
            (if (undefinedProperties.contains("octopusGithubDockerRegistry")) " OCTOPUS_GITHUB_DOCKER_REGISTRY" else "") +
            (if (undefinedProperties.contains("okdActiveDeadlineSeconds")) " OKD_ACTIVE_DEADLINE_SECONDS" else "") +
            (if (undefinedProperties.contains("okdProject")) " OKD_PROJECT" else "") +
            (if (undefinedProperties.contains("okdClusterDomain")) " OKD_CLUSTER_DOMAIN" else ""),
    )
}

fun String.getExt() = project.ext[this].toString()

val commonOkdParameters = mapOf(
    "ACTIVE_DEADLINE_SECONDS" to "okdActiveDeadlineSeconds".getExt(),
    "DOCKER_REGISTRY" to "dockerRegistry".getExt(),
)

ocTemplate {
    workDir.set(layout.buildDirectory.dir("okd"))
    clusterDomain.set("okdClusterDomain".getExt())
    namespace.set("okdProject".getExt())
    prefix.set("tc-auto")

    "okdWebConsoleUrl".getExt().takeIf { it.isNotBlank() }?.let {
        webConsoleUrl.set(it)
    }

    group("teamcityPVCs").apply {
        service("teamcity22-pvc") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-pvc.yaml"))
            parameters.set(
                mapOf(
                    "TEAMCITY_ID" to "22",
                ),
            )
        }
        service("teamcity26-pvc") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-pvc.yaml"))
            parameters.set(
                mapOf(
                    "TEAMCITY_ID" to "26",
                ),
            )
        }
    }

    group("teamcitySeedUploaders").apply {
        service("teamcity22-uploader") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-uploader.yaml"))
            parameters.set(
                commonOkdParameters + mapOf(
                    "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                    "TEAMCITY_ID" to "22",
                ),
            )
            waitForCompletion.set(true)
        }
        service("teamcity26-uploader") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-uploader.yaml"))
            parameters.set(
                commonOkdParameters + mapOf(
                    "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                    "TEAMCITY_ID" to "26",
                ),
            )
            waitForCompletion.set(true)
        }
    }

    group("teamcityServers").apply {
        service("teamcity22") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity.yaml"))
            parameters.set(
                commonOkdParameters + mapOf(
                    "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                    "TEAMCITY_IMAGE_TAG" to properties["teamcity-2022.image-tag"] as String,
                    "TEAMCITY_ID" to "22",
                    // Requests kept low (limits unchanged) per DevOps: 6-core nodes,
                    // a ~half-core request is too much to schedule a dev-env app and
                    // left teamcity22 stuck in Pending on the shared test-env namespace.
                    "CPU_REQUEST" to "50m",
                    "CPU_LIMIT" to "4000m",
                    "MEM_REQUEST" to "1.5Gi",
                    "MEM_LIMIT" to "3Gi",
                ),
            )
        }
        service("teamcity26") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity.yaml"))
            parameters.set(
                commonOkdParameters + mapOf(
                    "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                    "TEAMCITY_IMAGE_TAG" to project.properties["teamcity-2026.image-tag"] as String,
                    "TEAMCITY_ID" to "26",
                    "CPU_REQUEST" to "50m",
                    "CPU_LIMIT" to "2500m",
                    "MEM_REQUEST" to "1.5Gi",
                    "MEM_LIMIT" to "3Gi",
                ),
            )
        }
    }

    group("componentsRegistry").apply {
        service("comp-reg") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/components-registry.yaml"))
            val componentsRegistryWorkDir = layout.projectDirectory
                .dir("cli/src/test/resources/components-registry")
                .asFile.absolutePath
            parameters.set(
                commonOkdParameters + mapOf(
                    "COMPONENTS_REGISTRY_SERVICE_VERSION" to properties["octopus-components-registry-service.version"] as String,
                    "AGGREGATOR_GROOVY_CONTENT" to file("$componentsRegistryWorkDir/Aggregator.groovy").readText(),
                    "DEFAULTS_GROOVY_CONTENT" to file("$componentsRegistryWorkDir/Defaults.groovy").readText(),
                    "TEST_COMPONENTS_GROOVY_CONTENT" to file("$componentsRegistryWorkDir/TestComponents.groovy").readText(),
                    "APPLICATION_DEV_CONTENT" to layout.projectDirectory
                        .dir("docker/components-registry-service.yaml")
                        .asFile
                        .readText(),
                ),
            )
        }
    }
}

configure<ComposeExtension> {
    useComposeFiles.add(
        layout.projectDirectory
            .file("docker/docker-compose.yml")
            .asFile.path,
    )
    waitForTcpPorts.set(true)
    captureContainersOutputToFiles.set(layout.buildDirectory.dir("docker-logs"))
    environment.putAll(
        mapOf(
            "DOCKER_REGISTRY" to "dockerRegistry".getExt(),
            "TEAMCITY_2022_IMAGE_TAG" to properties["teamcity-2022.image-tag"],
            "TEAMCITY_2026_IMAGE_TAG" to properties["teamcity-2026.image-tag"],
            "COMPONENTS_REGISTRY_SERVICE_VERSION" to properties["octopus-components-registry-service.version"],
        ),
    )
}

val copyFilesTeamcity2022 = tasks.register<Exec>("copyFilesTeamcity2022") {
    dependsOn("ocCreateTeamcityPVCs", "ocCreateTeamcitySeedUploaders")
    val localFile = layout.projectDirectory
        .dir("docker/data.zip")
        .asFile.absolutePath
    commandLine(
        "oc",
        "cp",
        localFile,
        "-n",
        "okdProject".getExt(),
        "${ocTemplate.getPod("teamcity22-uploader")}:/seed/seed.zip",
    )
}

val copyFilesTeamcity2026 = tasks.register<Exec>("copyFilesTeamcity2026") {
    dependsOn("ocCreateTeamcityPVCs", "ocCreateTeamcitySeedUploaders")
    val localFile = layout.projectDirectory
        .dir("docker/dataV26.zip")
        .asFile.absolutePath
    commandLine(
        "oc",
        "cp",
        localFile,
        "-n",
        "okdProject".getExt(),
        "${ocTemplate.getPod("teamcity26-uploader")}:/seed/seed.zip",
    )
}

val seedTeamcity = tasks.register("seedTeamcity") {
    dependsOn(copyFilesTeamcity2022, copyFilesTeamcity2026)
    finalizedBy("ocLogsTeamcitySeedUploaders", "ocDeleteTeamcitySeedUploaders")
}

tasks.named("ocCreateTeamcityServers").configure {
    dependsOn(seedTeamcity)
}

tasks.named("ocDeleteTeamcityPVCs").configure {
    dependsOn("ocDeleteTeamcityServers")
}

// `finalizedBy` in a test task doesn't guarantee ordering within the finalizer
// set. Force `ocLogs*` to complete before `ocDelete*` so the CRS / TC server
// container logs are captured before the pods are torn down — otherwise
// ocLogsComponentsRegistry races with ocDeleteComponentsRegistry and the pod
// gets removed before kubectl can read its logs (observed on build 1.0.34-390).
tasks.named("ocDeleteComponentsRegistry").configure {
    mustRunAfter("ocLogsComponentsRegistry")
}
tasks.named("ocDeleteTeamcityServers").configure {
    mustRunAfter("ocLogsTeamcityServers")
}

// Both modules run integration tests against the same servers, so the servers come down only
// after every test task has finished, not when the first one does.
val integrationTestTasks = cliProjects.map { "${it.path}:test" }

// They also reset the same TeamCity parent project and template ids, so never run them at once.
project(":core").tasks.matching { it.name == "test" }.configureEach { mustRunAfter(":cli:test") }

listOf("composeDown", "ocLogsTeamcityServers", "ocLogsComponentsRegistry", "ocDeleteTeamcityPVCs", "ocDeleteComponentsRegistry")
    .forEach { name -> tasks.named(name).configure { mustRunAfter(integrationTestTasks) } }

val testPlatform = "testPlatform".getExt()
val rootOcTemplate = ocTemplate
val rootDockerCompose = dockerCompose

configure(cliProjects) {
    // Tests tagged "integration" need the TeamCity servers and the Components Registry; `unitTest` runs the
    // rest without starting them. `test` runs everything, as CI does.
    tasks.register<Test>("unitTest") {
        description = "Runs the tests that need no TeamCity or Components Registry."
        group = "verification"
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { excludeTags("integration") }
    }

    tasks.withType<Test>().matching { it.name == "test" }.configureEach {
        when (testPlatform) {
            "okd" -> {
                systemProperties["test.teamcity-2022-host"] = rootOcTemplate.getOkdHost("teamcity22")
                systemProperties["test.teamcity-2026-host"] = rootOcTemplate.getOkdHost("teamcity26")
                systemProperties["test.components-registry-host"] = rootOcTemplate.getOkdHost("comp-reg")
                dependsOn(":ocCreateTeamcityServers", ":ocCreateComponentsRegistry")
                finalizedBy(
                    ":ocLogsTeamcityServers",
                    ":ocLogsComponentsRegistry",
                    ":ocDeleteTeamcityPVCs",
                    ":ocDeleteComponentsRegistry",
                )
            }
            "docker" -> {
                systemProperties["test.teamcity-2022-host"] = "localhost:8111"
                systemProperties["test.teamcity-2026-host"] = "localhost:8112"
                systemProperties["test.components-registry-host"] = "localhost:4567"
                rootDockerCompose.isRequiredBy(this)
            }
        }
        useJUnitPlatform()
        testLogging {
            info.events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        }
    }
}

val prepareTeamcity2022Data = tasks.register<Sync>("prepareTeamcity2022Data") {
    from(zipTree(layout.projectDirectory.file("docker/data.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2022"))
}

val prepareTeamcity2026Data = tasks.register<Sync>("prepareTeamcity2026Data") {
    from(zipTree(layout.projectDirectory.file("docker/dataV26.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2026"))
}

tasks.named("composeUp") {
    dependsOn(prepareTeamcity2022Data)
    dependsOn(prepareTeamcity2026Data)
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("MAVEN_USERNAME"))
            password.set(System.getenv("MAVEN_PASSWORD"))
        }
    }
    transitionCheckOptions {
        maxRetries.set(60)
        delayBetween.set(Duration.ofSeconds(30))
    }
}

val testsExcludedFromBuild = gradle.startParameter.excludedTaskNames.any { excludedTask ->
    excludedTask == "test" || excludedTask.endsWith(":test")
}

configure(serviceProjects) {
    apply(plugin = "idea")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    group = "org.octopusden.octopus.teamcity-automation-service"

    configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${property("spring-boot.version")}")
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("spring-cloud.version")}")
        }
    }

    configure<org.gradle.plugins.ide.idea.model.IdeaModel> {
        module {
            isDownloadJavadoc = true
            isDownloadSources = true
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    extensions.configure<KotlinJvmProjectExtension> {
        // The Kotlin runtime the Spring BOMs pin, so the published POMs name the stdlib the service runs on.
        coreLibrariesVersion = providers.gradleProperty("kotlin.version").get()
        compilerOptions {
            languageVersion.set(KotlinVersion.KOTLIN_1_9)
            apiVersion.set(KotlinVersion.KOTLIN_1_9)
        }
    }

    // The imported Spring BOMs pin every org.jetbrains.kotlin artifact to the runtime version, and
    // dependency-management applies them to every configuration - including the Kotlin plugin's own
    // compiler, build-tools and compiler-plugin classpaths and detekt's and ktlint's, which must stay
    // at the versions those tools ask for. Registered after dependency-management's rule, this one
    // runs last. kotlinScriptDef* resolve from the project's classpath and stay managed.
    val toolConfigurationPrefixes = listOf("kotlin", "detekt", "ktlint")
    configurations
        .matching { c -> toolConfigurationPrefixes.any { c.name.startsWith(it) } && !c.name.startsWith("kotlinScriptDef") }
        .configureEach {
            resolutionStrategy.eachDependency {
                val version = requested.version
                if (requested.group.startsWith("org.jetbrains.kotlin") && !version.isNullOrEmpty()) {
                    useVersion(version)
                }
            }
        }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            info.events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
        }
    }

    if (testsExcludedFromBuild) {
        tasks.configureEach {
            if (name.startsWith("kover")) {
                enabled = false
            }
        }
    }
}

// The service's libraries other services consume: published to Maven Central and kept on Java 8.
configure(listOf(project(":common"), project(":client"))) {
    configure<JavaPluginExtension> {
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    val publishing = the<PublishingExtension>()
    publishing.publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set("Octopus module: ${project.name}")
                url.set("https://github.com/octopusden/octopus-teamcity-automation.git")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/octopusden/octopus-teamcity-automation.git")
                    connection.set("scm:git://github.com/octopusden/octopus-teamcity-automation.git")
                }
                developers {
                    developer {
                        id.set("octopus")
                        name.set("octopus")
                    }
                }
            }
        }
    }

    configure<SigningExtension> {
        isRequired = rootProject.ext["signingRequired"].toString().toBooleanStrict()
        useInMemoryPgpKeys(findProperty("signingKey") as String?, findProperty("signingPassword") as String?)
        sign(publishing.publications["maven"])
    }
}
