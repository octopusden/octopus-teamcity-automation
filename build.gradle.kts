import com.avast.gradle.dockercompose.ComposeExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("com.gradleup.shadow") apply false
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
            ),
        )
    }
    // Repo has no coverage tool configured — disable coverage verification.
    coverage {
        enabled.set(false)
    }
    // Enforce Kotlin static analysis (detekt + ktlint); current debt is absorbed by
    // detekt-baseline.xml / ktlint-baseline.xml so the gate stays green while enforcing.
    kotlin {
        failOnViolation.set(true)
    }
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
val integrationTestTasks = subprojects.map { "${it.path}:test" }

// They also reset the same TeamCity parent project and template ids, so never run them at once.
project(":core").tasks.matching { it.name == "test" }.configureEach { mustRunAfter(":cli:test") }

listOf("composeDown", "ocLogsTeamcityServers", "ocLogsComponentsRegistry", "ocDeleteTeamcityPVCs", "ocDeleteComponentsRegistry")
    .forEach { name -> tasks.named(name).configure { mustRunAfter(integrationTestTasks) } }

val testPlatform = "testPlatform".getExt()
val rootOcTemplate = ocTemplate
val rootDockerCompose = dockerCompose

subprojects {
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
