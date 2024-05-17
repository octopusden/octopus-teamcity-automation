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

tasks.register<Copy>("processMetarunners") {
    from("$projectDir/src/main/resources") {
        expand(rootProject.properties)
    }
    into("$projectDir/build/metarunners")
    rename("Runner.xml", "${project.properties["metarunnerId"]}.xml")
}

tasks.register("updateMetaRunners") {
    dependsOn("processMetarunners")
    doLast {
        val metarunnersDir = tasks.getByName("processMetarunners").outputs.files.singleFile
        fileTree(metarunnersDir).files.forEach {
            if (it.name.endsWith(".xml")) {
                val id = it.name.replace(".xml", "")
                println("Update metarunner $id")
                val content = it.readText(StandardCharsets.UTF_8)
                project.logger.info(
                    "Result:\n" +
                            post(
                                url = (project.properties["teamcity.url"] as String) + "/plugins/metarunner/runner-edit.html",
                                auth = BasicAuthorization(
                                    project.properties["teamcity.user"] as String,
                                    project.properties["teamcity.password"] as String
                                ),
                                headers = mapOf("Origin" to (project.properties["teamcity.url"] as String)),
                                data = mapOf(
                                    "projectId" to (project.properties["teamcity.project"] as String),
                                    "editRunnerId" to id,
                                    "metaRunnerContent" to content
                                )
                            ).text
                )
            }
        }
    }
}
