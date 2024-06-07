plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin")
    signing
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
    kotlinOptions.jvmTarget = "1.8"
}

java.targetCompatibility = JavaVersion.VERSION_1_8

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://packages.jetbrains.team/maven/p/teamcity-rest-client/teamcity-rest-client")
}

dependencies {
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("ch.qos.logback:logback-classic:1.3.14")
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.5")
    // TODO: +Custom dependencies
    implementation("org.jetbrains.teamcity:teamcity-rest-client:3.5")
}

application {
    mainClass = "${project.properties["mainClassPackage"]}.ApplicationKt"
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes(mapOf("Main-Class" to application.mainClass)) }
    val contents = configurations.runtimeClasspath.get().map { f ->
        if (f.isDirectory) f else zipTree(f)
    } + sourceSets.main.get().output
    from(contents)
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
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
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
                withXml {
                    val root = asNode()
                    val nodes = root["dependencies"] as groovy.util.NodeList
                    if (nodes.isNotEmpty()) {
                        root.remove(nodes.first() as groovy.util.Node)
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
