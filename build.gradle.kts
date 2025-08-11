import com.avast.gradle.dockercompose.ComposeExtension
import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    id("com.github.johnrengelman.shadow")
    id("com.avast.gradle.docker-compose")
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin")
    signing
    id("org.octopusden.octopus-release-management")
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

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        info.events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    systemProperties["jar"] = configurations["shadow"].artifacts.files.asPath
}

configure<ComposeExtension> {
    useComposeFiles.add(layout.projectDirectory.file("docker/docker-compose.yml").asFile.path)
    waitForTcpPorts.set(true)
    captureContainersOutputToFiles.set(layout.buildDirectory.dir("docker-logs"))
    environment.putAll(
        mapOf(
            "DOCKER_REGISTRY" to properties["docker.registry"],
            "TEAMCITY_VERSION" to "2022.04.7",
            "TEAMCITY_V25_VERSION" to "2025.03.3",
            "COMPONENTS_REGISTRY_SERVICE_VERSION" to properties["octopus-components-registry-service.version"],
        )
    )
}

dockerCompose.isRequiredBy(tasks["test"])

tasks.register<Sync>("prepareTeamcityServerData") {
    from(zipTree(layout.projectDirectory.file("docker/data.zip")))
    into(layout.buildDirectory.dir("teamcity-server"))
}

tasks.register<Sync>("prepareTeamcityServerDataV25") {
    from(zipTree(layout.projectDirectory.file("docker/dataV25.zip")))
    into(layout.buildDirectory.dir("teamcity-server-2025"))
}

tasks.named("composeUp") {
    dependsOn("prepareTeamcityServerData")
    dependsOn("prepareTeamcityServerDataV25")
}

dependencies {
    implementation("org.slf4j:slf4j-api:${properties["slf4j-api.version"]}")
    implementation("ch.qos.logback:logback-classic:${properties["logback-classic.version"]}")
    implementation("com.github.ajalt.clikt:clikt:${properties["clikt.version"]}")
    implementation("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${properties["teamcity-client.version"]}")
    implementation("org.octopusden.octopus.octopus-external-systems-clients:client-commons:${properties["teamcity-client.version"]}")
    implementation("org.octopusden.octopus.infrastructure:components-registry-service-client:${properties["octopus-components-registry-service.version"]}")
    implementation("org.octopusden.octopus.vcsfacade:client:${properties["octopus-vcs-facade.version"]}")
    implementation("org.octopusden.octopus.dms:client:${properties["octopus-dms-service.version"]}"){
        exclude("org.slf4j","slf4j-api")
        exclude("ch.qos.logback", "logback-classic")
    }
    implementation("org.octopusden.octopus.release-management-service:client:${properties["octopus-release-management-service.version"]}")
    implementation("com.atlassian.jira:jira-rest-java-client-core:${properties["jira-rest-client.version"]}")
    implementation("com.atlassian.jira:jira-rest-java-client-api:${properties["jira-rest-client.version"]}")
    implementation("io.atlassian.fugue:fugue:${properties["fugue.version"]}")
    implementation("org.glassfish.jersey.core:jersey-common:${properties["glassfish-jersey.version"]}")

    testImplementation("org.junit.jupiter:junit-jupiter-api:${property("junit.version")}")
    testImplementation("org.junit.jupiter:junit-jupiter-params:${property("junit.version")}")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:${property("junit.version")}")
    testImplementation("it.skrape:skrapeit:${property("skrapeit.version")}")
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
    isRequired = System.getenv().containsKey("ORG_GRADLE_PROJECT_signingKey") && System.getenv()
        .containsKey("ORG_GRADLE_PROJECT_signingPassword")
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}

tasks.distZip.get().isEnabled = false
tasks.shadowDistZip.get().isEnabled = false
tasks.distTar.get().isEnabled = false
tasks.shadowDistTar.get().isEnabled = false
