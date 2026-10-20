import com.avast.gradle.dockercompose.ComposeExtension

plugins {
    id("com.avast.gradle.docker-compose")
    id("org.octopusden.octopus.oc-template")
}

val ftExcludedFromBuild = gradle.startParameter.excludedTaskNames.any { excludedTask ->
    excludedTask == "test" ||
        excludedTask.endsWith(":test") ||
        excludedTask == "ft" ||
        excludedTask.endsWith(":ft")
}

fun String.getExt() = rootProject.ext[this].toString()

sourceSets {
    create("ft") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val ftImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

configurations["ftRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

kover {
    currentProject {
        instrumentation {
            // ft needs Docker or OKD and is run on its own; without this, koverVerify (and so build
            // and qualityCoverage) would start the whole environment.
            disabledForTestTasks.add("ft")
        }
    }
}

// The TeamCity seeds and the Components Registry setup are the ones the :core and :cli integration tests use.
val sharedDockerDir = rootProject.layout.projectDirectory.dir("docker")
val componentsRegistryDir = rootProject.layout.projectDirectory.dir("cli/src/test/resources/components-registry")
val componentsRegistryConfig = sharedDockerDir.file("components-registry-service.yaml")
val serviceConfig = layout.projectDirectory.file("docker/teamcity-automation-service.yaml")

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
            "TEAMCITY_AUTOMATION_SERVICE_VERSION" to version,
            "OCTOPUS_COMPONENTS_REGISTRY_SERVICE_VERSION" to providers.gradleProperty("octopus-components-registry-service.version").get(),
            "TEAMCITY_2022_IMAGE_TAG" to providers.gradleProperty("teamcity-2022.image-tag").get(),
            "TEAMCITY_2026_IMAGE_TAG" to providers.gradleProperty("teamcity-2026.image-tag").get(),
            "DOCKER_REGISTRY" to "dockerRegistry".getExt(),
            "OCTOPUS_GITHUB_DOCKER_REGISTRY" to "octopusGithubDockerRegistry".getExt(),
        ),
    )
}

val prepareTeamcity2022Data = tasks.register<Sync>("prepareTeamcity2022Data") {
    from(zipTree(sharedDockerDir.file("data.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2022"))
}

val prepareTeamcity2026Data = tasks.register<Sync>("prepareTeamcity2026Data") {
    from(zipTree(sharedDockerDir.file("dataV26.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2026"))
}

tasks.named("composeUp") {
    dependsOn(":teamcity-automation-service:dockerBuildImage")
    dependsOn(prepareTeamcity2022Data, prepareTeamcity2026Data)
}

// OKD: the same environment as pods of one namespace, created from okd/*.yaml by the oc-template plugin.

fun String.getPort() =
    when (this) {
        "teamcity22", "teamcity26" -> 8111
        "comp-reg" -> 4567
        "tas" -> 8080
        else -> throw GradleException("Unknown service '$this'")
    }

/** The in-namespace address the service uses to reach another pod. */
fun getOkdInternalHost(serviceName: String) = "${ocTemplate.getPod(serviceName)}-service:${serviceName.getPort()}"

val commonOkdParameters = mapOf(
    "ACTIVE_DEADLINE_SECONDS" to "okdActiveDeadlineSeconds".getExt(),
    "DOCKER_REGISTRY" to "dockerRegistry".getExt(),
)

val serviceAccountAnyuid = project.properties["okd.service-account-anyuid"] as String

ocTemplate {
    workDir.set(layout.buildDirectory.dir("okd"))
    clusterDomain.set("okdClusterDomain".getExt())
    namespace.set("okdProject".getExt())
    prefix.set("tas-ft")

    "okdWebConsoleUrl".getExt().takeIf { it.isNotBlank() }?.let {
        webConsoleUrl.set(it)
    }

    group("teamcityPVCs").apply {
        listOf("22", "26").forEach { id ->
            service("teamcity$id-pvc") {
                templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-pvc.yaml"))
                parameters.set(mapOf("TEAMCITY_ID" to id))
            }
        }
    }

    group("teamcitySeedUploaders").apply {
        listOf("22", "26").forEach { id ->
            service("teamcity$id-uploader") {
                templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-uploader.yaml"))
                parameters.set(
                    commonOkdParameters + mapOf(
                        "SERVICE_ACCOUNT_ANYUID" to serviceAccountAnyuid,
                        "TEAMCITY_ID" to id,
                    ),
                )
                waitForCompletion.set(true)
            }
        }
    }

    group("teamcityServers").apply {
        // Requests kept low, limits as in octopus-teamcity-automation: the shared test namespace runs on small nodes.
        mapOf(
            "22" to Pair(properties["teamcity-2022.image-tag"] as String, "4000m"),
            "26" to Pair(properties["teamcity-2026.image-tag"] as String, "2500m"),
        ).forEach { (id, image) ->
            service("teamcity$id") {
                templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity.yaml"))
                parameters.set(
                    commonOkdParameters + mapOf(
                        "SERVICE_ACCOUNT_ANYUID" to serviceAccountAnyuid,
                        "TEAMCITY_IMAGE_TAG" to image.first,
                        "TEAMCITY_ID" to id,
                        "CPU_REQUEST" to "50m",
                        "CPU_LIMIT" to image.second,
                        "MEM_REQUEST" to "1.5Gi",
                        "MEM_LIMIT" to "3Gi",
                    ),
                )
            }
        }
    }

    group("componentsRegistry").apply {
        service("comp-reg") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/components-registry.yaml"))
            parameters.set(
                commonOkdParameters + mapOf(
                    "COMPONENTS_REGISTRY_SERVICE_VERSION" to properties["octopus-components-registry-service.version"] as String,
                    "AGGREGATOR_GROOVY_CONTENT" to componentsRegistryDir.file("Aggregator.groovy").asFile.readText(),
                    "DEFAULTS_GROOVY_CONTENT" to componentsRegistryDir.file("Defaults.groovy").asFile.readText(),
                    "TEST_COMPONENTS_GROOVY_CONTENT" to componentsRegistryDir.file("TestComponents.groovy").asFile.readText(),
                    "APPLICATION_DEV_CONTENT" to componentsRegistryConfig.asFile.readText(),
                ),
            )
        }
    }

    group("teamcityAutomationService").apply {
        service("tas") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-automation-service.yaml"))
            parameters.set(
                commonOkdParameters + mapOf(
                    "OCTOPUS_GITHUB_DOCKER_REGISTRY" to "octopusGithubDockerRegistry".getExt(),
                    "TEAMCITY_AUTOMATION_SERVICE_VERSION" to version as String,
                    "APPLICATION_FT_CONTENT" to serviceConfig.asFile.readText(),
                ),
            )
        }
    }
}

listOf("22", "26").forEach { id ->
    tasks.register<Exec>("copyFilesTeamcity$id") {
        dependsOn("ocCreateTeamcityPVCs", "ocCreateTeamcitySeedUploaders")
        commandLine(
            "oc",
            "cp",
            sharedDockerDir
                .file(if (id == "22") "data.zip" else "dataV26.zip")
                .asFile.absolutePath,
            "-n",
            "okdProject".getExt(),
            "${ocTemplate.getPod("teamcity$id-uploader")}:/seed/seed.zip",
        )
    }
}

val seedTeamcity = tasks.register("seedTeamcity") {
    dependsOn("copyFilesTeamcity22", "copyFilesTeamcity26")
    finalizedBy("ocLogsTeamcitySeedUploaders", "ocDeleteTeamcitySeedUploaders")
}

tasks.named("ocCreateTeamcityServers").configure {
    dependsOn(seedTeamcity)
}

tasks.named("ocCreateTeamcityAutomationService").configure {
    // The pod pulls the image under test, so it has to be in the registry first.
    dependsOn(":teamcity-automation-service:dockerPushImage")
}

tasks.named("ocDeleteTeamcityPVCs").configure {
    dependsOn("ocDeleteTeamcityServers")
}

// Logs before deletion: finalizedBy alone does not order the finalizers, and a deleted pod has no logs.
mapOf(
    "ocDeleteTeamcityServers" to "ocLogsTeamcityServers",
    "ocDeleteComponentsRegistry" to "ocLogsComponentsRegistry",
    "ocDeleteTeamcityAutomationService" to "ocLogsTeamcityAutomationService",
).forEach { (delete, logs) ->
    tasks.named(delete).configure { mustRunAfter(logs) }
}

val ft by tasks.creating(Test::class) {
    group = "verification"
    description = "Runs the functional tests"

    testClassesDirs = sourceSets["ft"].output.classesDirs
    classpath = sourceSets["ft"].runtimeClasspath

    // Hosts as the tests reach them, and as the service reaches TeamCity and the registry.
    when ("testPlatform".getExt()) {
        "okd" -> {
            systemProperties["test.teamcity-automation-service-host"] = ocTemplate.getOkdHost("tas")
            systemProperties["test.teamcity-2022-host"] = ocTemplate.getOkdHost("teamcity22")
            systemProperties["test.teamcity-2026-host"] = ocTemplate.getOkdHost("teamcity26")
            systemProperties["test.teamcity-2022-host-for-service"] = getOkdInternalHost("teamcity22")
            systemProperties["test.teamcity-2026-host-for-service"] = getOkdInternalHost("teamcity26")
            systemProperties["test.components-registry-host-for-service"] = getOkdInternalHost("comp-reg")
            dependsOn(
                "ocCreateTeamcityServers",
                "ocCreateComponentsRegistry",
                "ocCreateTeamcityAutomationService",
            )
            finalizedBy(
                "ocLogsTeamcityServers",
                "ocLogsComponentsRegistry",
                "ocLogsTeamcityAutomationService",
                "ocDeleteTeamcityPVCs",
                "ocDeleteComponentsRegistry",
                "ocDeleteTeamcityAutomationService",
            )
        }
        "docker" -> {
            systemProperties["test.teamcity-automation-service-host"] = "localhost:8080"
            systemProperties["test.teamcity-2022-host"] = "localhost:8111"
            systemProperties["test.teamcity-2026-host"] = "localhost:8112"
            systemProperties["test.teamcity-2022-host-for-service"] = "teamcity-2022:8111"
            systemProperties["test.teamcity-2026-host-for-service"] = "teamcity-2026:8111"
            systemProperties["test.components-registry-host-for-service"] = "components-registry-service:4567"
            finalizedBy("composeLogs")
        }
    }
}

if ("testPlatform".getExt() == "docker") {
    configure<ComposeExtension> {
        isRequiredBy(ft)
    }
}

val ftInfrastructureTasks = setOf(
    "ft",
    "prepareTeamcity2022Data",
    "prepareTeamcity2026Data",
    "copyFilesTeamcity22",
    "copyFilesTeamcity26",
    "seedTeamcity",
)

if (ftExcludedFromBuild) {
    tasks.configureEach {
        if (name in ftInfrastructureTasks || name.startsWith("compose") || name.startsWith("oc")) {
            enabled = false
        }
    }
}

idea.module {
    // The resolvable view of ftImplementation: a declare-only configuration cannot be resolved.
    scopes["PROVIDED"]?.get("plus")?.add(configurations["ftCompileClasspath"])
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${properties["spring-boot.version"]}")
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${properties["spring-cloud.version"]}")
    }
}

dependencies {
    ftImplementation(project(":client"))
    ftImplementation(project(":common"))
    ftImplementation("org.junit.jupiter:junit-jupiter-engine")
    ftImplementation("org.junit.jupiter:junit-jupiter-params")
    ftImplementation("com.fasterxml.jackson.core:jackson-databind")
    // Prepares the parent project and reads back what the service created.
    ftImplementation("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${properties["teamcity-client.version"]}")
    "ftRuntimeOnly"("ch.qos.logback:logback-classic")
    // Gradle no longer puts the JUnit Platform launcher on the test runtime classpath itself.
    "ftRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
