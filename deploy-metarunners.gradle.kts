import java.nio.charset.StandardCharsets
import khttp.post
import khttp.structures.authorization.BasicAuthorization

buildscript {
    dependencies {
        classpath("org.danilopianini:khttp:1.2.2")
    }
    repositories {
        mavenCentral()
    }
}

tasks.register("updateMetaRunners") {
    doLast {
        fileTree("${rootDir}/build/metarunners").files.forEach {
            if (it.name.endsWith(".xml")) {
                val id = it.name.replace(".xml", "")
                println("Update metarunner $id")
                val content = it.readText(StandardCharsets.UTF_8)
                project.logger.info(
                    "Result:\n" +
                            post(
                                url = (project.properties["TEAMCITY_URL"] as String) + "/plugins/metarunner/runner-edit.html",
                                auth = BasicAuthorization(
                                    project.properties["TEAMCITY_USER"] as String,
                                    project.properties["TEAMCITY_PASSWORD"] as String
                                ),
                                headers = mapOf("Origin" to (project.properties["TEAMCITY_URL"] as String)),
                                data = mapOf(
                                    "projectId" to (project.properties["TEAMCITY_PROJECT"] as String),
                                    "editRunnerId" to id,
                                    "metaRunnerContent" to content
                                )
                            ).text
                )
            }
        }
    }
}