import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin")
    signing
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

// TODO: +Settings
group = "org.octopusden.octopus.automation"
description = "Octopus Teamcity Automation"
ext {
    set("metarunnerId", "OctopusTeamcityAutomation")
    set("mainClassPackage", "org.octopusden.octopus.automation.teamcity")
    set("artifactId", "teamcity-automation")
}

tasks.compileKotlin {
    kotlinOptions {
        suppressWarnings = true
        jvmTarget = "1.8"
    }
}

java.targetCompatibility = JavaVersion.VERSION_1_8

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.5")
    // TODO: +Custom dependencies
    implementation("org.octopusden.octopus.infrastructure:components-registry-service-client:${project.properties["components-registry-service-client.version"]}")
    implementation("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${project.properties["teamcity-client.version"]}")
    implementation("org.apache.commons:commons-text:1.12.0")
}

application {
    mainClass = "${project.properties["mainClassPackage"]}.ApplicationKt"
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes(mapOf("Main-Class" to application.mainClass)) }
    archiveBaseName = project.properties["artifactId"] as String
}

java {
    withJavadocJar()
    withSourcesJar()
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
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
            project.shadow.component(this)
            artifact(tasks["javadocJar"])
            artifact(tasks["sourcesJar"])
            artifactId = project.properties["artifactId"] as String
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
