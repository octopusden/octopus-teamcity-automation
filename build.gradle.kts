import com.avast.gradle.dockercompose.ComposeExtension
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.net.InetAddress
import java.util.zip.CRC32

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    id("com.github.johnrengelman.shadow")
    id("com.avast.gradle.docker-compose")
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin")
    signing
    id("org.octopusden.octopus-release-management")
    id("org.octopusden.octopus.oc-template")
}

val defaultVersion = "${
    with(CRC32()) {
        update(InetAddress.getLocalHost().hostName.toByteArray())
        value
    }
}-snapshot"

if (version == "unspecified") {
    version = defaultVersion
}

group = "org.octopusden.octopus.automation.teamcity"
description = "Octopus Teamcity Automation"

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        suppressWarnings = true
        jvmTarget = "1.8"
    }
}

java.targetCompatibility = JavaVersion.VERSION_1_8

repositories {
    mavenCentral()
}

ext {
    System.getenv().let {
        set("signingRequired", it.containsKey("ORG_GRADLE_PROJECT_signingKey") && it.containsKey("ORG_GRADLE_PROJECT_signingPassword"))
        set("testPlatform", it.getOrDefault("TEST_PLATFORM", properties["test.platform"]))
        set("dockerRegistry", it.getOrDefault("DOCKER_REGISTRY", properties["docker.registry"]))
        set("octopusGithubDockerRegistry", it.getOrDefault("OCTOPUS_GITHUB_DOCKER_REGISTRY", project.properties["octopus.github.docker.registry"]))
        set("okdActiveDeadlineSeconds", it.getOrDefault("OKD_ACTIVE_DEADLINE_SECONDS", properties["okd.active-deadline-seconds"]))
        set("okdProject", it.getOrDefault("OKD_PROJECT", properties["okd.project"]))
        set("okdClusterDomain", it.getOrDefault("OKD_CLUSTER_DOMAIN", properties["okd.cluster-domain"]))
        set("okdWebConsoleUrl", (it.getOrDefault("OKD_WEB_CONSOLE_URL", properties["okd.web-console-url"]) as String).trimEnd('/'))
    }
}
val supportedTestPlatforms = listOf("docker", "okd")
if (project.ext["testPlatform"] !in supportedTestPlatforms) {
    throw IllegalArgumentException("Test platform must be set to one of the following $supportedTestPlatforms. Start gradle build with -Ptest.platform=... or set env variable TEST_PLATFORM")
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
                (if (undefinedProperties.contains("okdClusterDomain")) " OKD_CLUSTER_DOMAIN" else "")
    )
}
fun String.getExt() = project.ext[this].toString()

val commonOkdParameters = mapOf(
    "ACTIVE_DEADLINE_SECONDS" to "okdActiveDeadlineSeconds".getExt(),
    "DOCKER_REGISTRY" to "dockerRegistry".getExt()
)

ocTemplate {
    workDir.set(layout.buildDirectory.dir("okd"))
    clusterDomain.set("okdClusterDomain".getExt())
    namespace.set("okdProject".getExt())
    prefix.set("tc-auto")
    projectVersion.set(defaultVersion)

    "okdWebConsoleUrl".getExt().takeIf { it.isNotBlank() }?.let{
        webConsoleUrl.set(it)
    }

    group("teamcityPVCs").apply {
        service("teamcity22-pvc") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-pvc.yaml"))
            parameters.set(mapOf(
                "TEAMCITY_ID" to "22"
            ))
        }
        service("teamcity25-pvc") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-pvc.yaml"))
            parameters.set(mapOf(
                "TEAMCITY_ID" to "25"
            ))
        }
    }

    group("teamcitySeedUploaders").apply {
        service("teamcity22-uploader") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-uploader.yaml"))
            parameters.set(mapOf(
                "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                "ACTIVE_DEADLINE_SECONDS" to "okdActiveDeadlineSeconds".getExt(),
                "TEAMCITY_ID" to "22"
            ))
        }
        service("teamcity25-uploader") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity-uploader.yaml"))
            parameters.set(mapOf(
                "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                "ACTIVE_DEADLINE_SECONDS" to "okdActiveDeadlineSeconds".getExt(),
                "TEAMCITY_ID" to "25"
            ))
        }
    }

    group("teamcityServers").apply {
        service("teamcity22") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity.yaml"))
            parameters.set(commonOkdParameters + mapOf(
                "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                "TEAMCITY_IMAGE_TAG" to properties["teamcity-2022.image-tag"] as String,
                "TEAMCITY_ID" to "22"
            ))
        }
        service("teamcity25") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/teamcity.yaml"))
            parameters.set(commonOkdParameters + mapOf(
                "SERVICE_ACCOUNT_ANYUID" to project.properties["okd.service-account-anyuid"] as String,
                "TEAMCITY_IMAGE_TAG" to project.properties["teamcity-2025.image-tag"] as String,
                "TEAMCITY_ID" to "25"
            ))
        }
    }

    group("componentsRegistry").apply {
        service("comp-reg") {
            templateFile.set(rootProject.layout.projectDirectory.file("okd/components-registry.yaml"))
            parameters.set(commonOkdParameters + mapOf(
                "COMPONENTS_REGISTRY_SERVICE_VERSION" to properties["octopus-components-registry-service.version"] as String,
                "CONFIGMAP_NAME" to "tc-auto-comp-reg-config-$defaultVersion"
            ))
        }
    }
}

configure<ComposeExtension> {
    useComposeFiles.add(layout.projectDirectory.file("docker/docker-compose.yml").asFile.path)
    waitForTcpPorts.set(true)
    captureContainersOutputToFiles.set(layout.buildDirectory.dir("docker-logs"))
    environment.putAll(
        mapOf(
            "DOCKER_REGISTRY" to "dockerRegistry".getExt(),
            "TEAMCITY_2022_IMAGE_TAG" to properties["teamcity-2022.image-tag"],
            "TEAMCITY_2025_IMAGE_TAG" to properties["teamcity-2025.image-tag"],
            "COMPONENTS_REGISTRY_SERVICE_VERSION" to properties["octopus-components-registry-service.version"]
        )
    )
}

val copyFilesTeamcity2022 = tasks.register<Exec>("copyFilesTeamcity2022") {
    dependsOn("ocCreateTeamcityPVCs", "ocCreateTeamcitySeedUploaders")
    val localFile = layout.projectDirectory.dir("docker/data.zip").asFile.absolutePath
    commandLine("oc", "cp", localFile, "-n", "okdProject".getExt(),
        "${ocTemplate.getPod("teamcity22-uploader")}:/seed/seed.zip")
}

val copyFilesTeamcity2025 = tasks.register<Exec>("copyFilesTeamcity2025") {
    dependsOn("ocCreateTeamcityPVCs", "ocCreateTeamcitySeedUploaders")
    val localFile = layout.projectDirectory.dir("docker/dataV25.zip").asFile.absolutePath
    commandLine("oc", "cp", localFile, "-n", "okdProject".getExt(),
        "${ocTemplate.getPod("teamcity25-uploader")}:/seed/seed.zip")
}

val createConfigMapComponentsRegistry = tasks.register<Exec>("createConfigMapComponentsRegistry") {
    val applicationFtFile = layout.projectDirectory.dir("docker/components-registry-service.yaml").asFile.absolutePath
    val componentsRegistryFiles = layout.projectDirectory.dir("src/test/resources/components-registry").asFile.absolutePath
    commandLine(
        "oc", "create", "configmap", "tc-auto-comp-reg-config-$defaultVersion",
        "--from-file=application-ft.yaml=$applicationFtFile",
        "--from-file=Aggregator.groovy=$componentsRegistryFiles/Aggregator.groovy",
        "--from-file=Defaults.groovy=$componentsRegistryFiles/Defaults.groovy",
        "--from-file=TestComponents.groovy=$componentsRegistryFiles/TestComponents.groovy",
        "-n", "okdProject".getExt()
    )
}

val deleteConfigMapComponentsRegistry = tasks.register<Exec>("deleteConfigMapComponentsRegistry") {
    commandLine(
        "oc", "delete", "configmap", "tc-auto-comp-reg-config-$defaultVersion",
        "-n", "okdProject".getExt()
    )
}

val seedTeamcity = tasks.register("seedTeamcity") {
    dependsOn(copyFilesTeamcity2022, copyFilesTeamcity2025)
    finalizedBy("ocLogsTeamcitySeedUploaders", "ocDeleteTeamcitySeedUploaders")
}

tasks.named("ocCreateTeamcityServers").configure {
    dependsOn(seedTeamcity)
}

tasks.withType<Test> {
    when ("testPlatform".getExt()) {
        "okd" -> {
            systemProperties["test.teamcity-2022-host"] = ocTemplate.getOkdHost("teamcity22")
            systemProperties["test.teamcity-2025-host"] = ocTemplate.getOkdHost("teamcity25")
            systemProperties["test.components-registry-host"] = ocTemplate.getOkdHost("comp-reg")

            useJUnitPlatform()
            testLogging {
                info.events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
            }
            systemProperties["jar"] = configurations["shadow"].artifacts.files.asPath

            dependsOn(
                createConfigMapComponentsRegistry,
                "ocCreateTeamcityServers",
                "ocCreateComponentsRegistry",
                "shadowJar"
            )
            finalizedBy(
                "ocLogsTeamcityServers",
                "ocLogsComponentsRegistry",
                "ocDeleteTeamcityServers",
                "ocDeleteTeamcityPVCs",
                "ocDeleteComponentsRegistry",
                deleteConfigMapComponentsRegistry
            )
        }
        "docker" -> {
            systemProperties["test.teamcity-2022-host"] = "localhost:8111"
            systemProperties["test.teamcity-2025-host"] = "localhost:8112"
            systemProperties["test.components-registry-host"] = "localhost:4567"
            dependsOn("shadowJar")
            useJUnitPlatform()
            testLogging {
                info.events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
            }
            systemProperties["jar"] = configurations["shadow"].artifacts.files.asPath
            dockerCompose.isRequiredBy(this)
        }
    }
}

tasks.named("composeUp") {
    dependsOn(prepareTeamcity2022Data)
    dependsOn(prepareTeamcity2025Data)
}

val prepareTeamcity2022Data = tasks.register<Sync>("prepareTeamcity2022Data") {
    from(zipTree(layout.projectDirectory.file("docker/data.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2022"))
}

val prepareTeamcity2025Data = tasks.register<Sync>("prepareTeamcity2025Data") {
    from(zipTree(layout.projectDirectory.file("docker/dataV25.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2025"))
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.3.14")
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${properties["teamcity-client.version"]}")
    implementation("org.octopusden.octopus.infrastructure:components-registry-service-client:${properties["octopus-components-registry-service.version"]}")
    with("5.9.2") {
        testImplementation("org.junit.jupiter:junit-jupiter-api:$this")
        testImplementation("org.junit.jupiter:junit-jupiter-params:$this")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$this")
    }
    testImplementation("it.skrape:skrapeit:1.2.2")
}

application {
    mainClass = "$group.ApplicationKt"
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes(mapOf("Main-Class" to application.mainClass)) }
}

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.register<Zip>("zipMetarunners") {
    archiveFileName = "metarunners.zip"
    from(layout.projectDirectory.dir("metarunners")) {
        expand(properties)
    }
}

configurations {
    create("distributions")
}

val metarunners = artifacts.add(
    "distributions",
    layout.buildDirectory.file("distributions/metarunners.zip").get().asFile
) {
    classifier = "metarunners"
    type = "zip"
    builtBy("zipMetarunners")
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(metarunners)
            pom {
                name.set(project.name)
                description.set(project.description)
                url.set("https://github.com/octopusden/${project.name}.git")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/octopusden/${project.name}.git")
                    connection.set("scm:git://github.com/octopusden/${project.name}.git")
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
}

signing {
    isRequired = "signingRequired".getExt().toBooleanStrict()
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}

tasks.distZip.get().isEnabled = false
tasks.shadowDistZip.get().isEnabled = false
tasks.distTar.get().isEnabled = false
tasks.shadowDistTar.get().isEnabled = false
