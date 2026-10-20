plugins {
    `maven-publish`
}

dependencyManagement {
    imports {
        mavenBom("io.github.openfeign:feign-bom:${properties["openfeign.version"]}")
    }
}

dependencies {
    api(project(":common"))
    api("io.github.openfeign:feign-httpclient")
    api("io.github.openfeign:feign-jackson")
    api("io.github.openfeign:feign-slf4j")
    api("org.apache.httpcomponents:httpclient:4.5.13")
    api("com.fasterxml.jackson.module:jackson-module-kotlin")
    api("com.fasterxml.jackson.core:jackson-databind")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle no longer puts the JUnit Platform launcher on the test runtime classpath itself.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
