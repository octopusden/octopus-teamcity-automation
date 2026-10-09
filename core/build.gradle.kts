plugins {
    `java-library`
}

description = "TeamCity automation: build chains, VCS root replacement, parameters, metarunners, agent requirements, GitHub commit statuses"

// The project path stays short (`:core`); the published name says what it is.
val publishedName = "octopus-teamcity-automation-core"

base {
    archivesName.set(publishedName)
}

dependencies {
    // Both clients appear in public constructors (BuildChainCreator and friends), so callers compile against them.
    api("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${properties["teamcity-client.version"]}")
    api(
        "org.octopusden.octopus.infrastructure:components-registry-service-client:" +
            "${properties["octopus-components-registry-service-client.version"]}",
    )
    implementation("org.slf4j:slf4j-api:2.0.13")
    // CommitStatusPublisher exposes only its own types, so these stay off the callers' compile classpath.
    implementation("org.kohsuke:github-api:${properties["github-api.version"]}")
    implementation("com.squareup.okhttp3:okhttp:${properties["okhttp.version"]}")
    with("5.9.2") {
        testImplementation("org.junit.jupiter:junit-jupiter-api:$this")
        testImplementation("org.junit.jupiter:junit-jupiter-params:$this")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$this")
    }
    testRuntimeOnly("ch.qos.logback:logback-classic:1.3.14")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = publishedName
            from(components["java"])
            pom {
                name.set(publishedName)
                description.set(project.description)
                url.set("https://github.com/octopusden/${rootProject.name}.git")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/octopusden/${rootProject.name}.git")
                    connection.set("scm:git://github.com/octopusden/${rootProject.name}.git")
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
    isRequired = rootProject.ext["signingRequired"].toString().toBooleanStrict()
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["maven"])
}
