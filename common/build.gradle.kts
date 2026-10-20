plugins {
    `maven-publish`
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-annotations")
    implementation("jakarta.validation:jakarta.validation-api:${properties["jakarta-validation.version"]}")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle no longer puts the JUnit Platform launcher on the test runtime classpath itself.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
