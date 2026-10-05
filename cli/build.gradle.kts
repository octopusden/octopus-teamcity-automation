plugins {
    application
    id("com.gradleup.shadow")
}

// The CLI was the root project before build-chain was split out. Its jar, its Maven coordinates
// and the metarunners (which download the fat jar by `${group}:${name}:${version}`) keep that
// name, so nothing that consumes them changes.
val publishedName = rootProject.name

base {
    archivesName.set(publishedName)
}

dependencies {
    implementation(project(":build-chain"))
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.3.14")
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.octopusden.octopus.octopus-external-systems-clients:teamcity-client:${properties["teamcity-client.version"]}")
    implementation(
        "org.octopusden.octopus.infrastructure:components-registry-service-client:" +
            "${properties["octopus-components-registry-service-client.version"]}",
    )
    implementation("org.kohsuke:github-api:${properties["github-api.version"]}")
    implementation("com.squareup.okhttp3:okhttp:${properties["okhttp.version"]}")
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

tasks.test {
    val jar = tasks.shadowJar.flatMap { it.archiveFile }.also { inputs.file(it) }
    systemProperties["jar"] = jar.get().asFile.absolutePath
}

tasks.register<Zip>("zipMetarunners") {
    archiveFileName = "metarunners.zip"
    from(rootProject.layout.projectDirectory.dir("metarunners")) {
        expand(properties + mapOf("name" to publishedName))
    }
}

configurations {
    create("distributions")
}

val metarunners = artifacts.add(
    "distributions",
    layout.buildDirectory
        .file("distributions/metarunners.zip")
        .get()
        .asFile,
) {
    classifier = "metarunners"
    type = "zip"
    builtBy("zipMetarunners")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = publishedName
            from(components["java"])
            artifact(metarunners)
            pom {
                name.set(publishedName)
                description.set(project.description)
                url.set("https://github.com/octopusden/$publishedName.git")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/octopusden/$publishedName.git")
                    connection.set("scm:git://github.com/octopusden/$publishedName.git")
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

tasks.distZip.get().isEnabled = false
tasks.shadowDistZip.get().isEnabled = false
tasks.distTar.get().isEnabled = false
tasks.shadowDistTar.get().isEnabled = false
