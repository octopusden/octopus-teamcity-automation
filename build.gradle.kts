import org.gradle.kotlin.dsl.support.kotlinCompilerOptions

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

tasks.compileKotlin{
    kotlinOptions.jvmTarget = "1.8"
}

java.targetCompatibility = JavaVersion.VERSION_1_8

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://packages.jetbrains.team/maven/p/teamcity-rest-client/teamcity-rest-client")
}

dependencies {
    implementation("org.slf4j:slf4j-api:1.7.25")
    implementation("org.slf4j:slf4j-simple:1.7.25")
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.5")
    // TODO: +Custom dependencies
    implementation("org.jetbrains.teamcity:teamcity-rest-client:3.5")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
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

tasks.register<Copy>("processMetarunners") {
    from("$rootDir/metarunners") {
        expand(rootProject.properties)
    }
    into("$rootDir/build/metarunners")
    rename("runner.xml", "${project.properties["metarunnerId"]}.xml")
}

tasks.processResources {
    dependsOn("processMetarunners")
}

apply("deploy-metarunners.gradle.kts")

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = project.properties["artifactId"] as String
        }
    }
}