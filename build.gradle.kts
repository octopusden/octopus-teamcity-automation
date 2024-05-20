plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    `maven-publish`
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
    implementation("ch.qos.logback:logback-core:${properties.get("logback-core.version")}")
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

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = project.properties["artifactId"] as String
        }
    }
}