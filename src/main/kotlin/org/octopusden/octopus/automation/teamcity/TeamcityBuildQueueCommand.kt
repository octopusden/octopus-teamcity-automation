package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.associate
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperties
import org.octopusden.octopus.infrastructure.teamcity.client.dto.TeamcityProperty
import org.octopusden.octopus.infrastructure.teamcity.client.dto.locator.BuildTypeLocator
import org.slf4j.Logger

class TeamcityBuildQueueCommand : CliktCommand(name = COMMAND, help = "Add a new build to the queue") {

    private val buildTypeId by option(BUILD_TYPE_ID, help = "TeamCity build configuration ID")
        .convert { it.trim() }.required()
        .check("$BUILD_TYPE_ID must not be empty") { it.isNotEmpty() }

    private val branch by option(BRANCH, help = "Specifies the branch name in VCS for this build")
        .convert { it.trim() }.required()
        .check("$BRANCH must not be empty") { it.isNotEmpty() }

    private val comment by option(COMMENT, help = "Optional comment to include with the queued build")
        .convert { it.trim() }

    private val parameters by option(PARAMETERS, help = "Additional build parameters as key=value pairs (e.g. key1=val1,key2=val2)")
        .associate()

    private val context by requireObject<MutableMap<String, Any>>()
    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }
    private val log by lazy { context[TeamcityCommand.LOG] as Logger }

    override fun run() {
        val props = if (parameters.isNotEmpty()) {
            TeamcityProperties(parameters.map { TeamcityProperty(name = it.key, value = it.value) })
        } else null
//        val toQueue = TeamcityQueuedBuild(
//            buildType  = BuildTypeLocator(id = buildTypeId),
//            branchName = branch,
//            comment    = comment?.let { TeamcityBuildComment(it) },
//            properties = props
//        )
//        val queued = client.queueBuild(toQueue)
//
//        log.info("Build queued: id={}, state={}", queued.id, queued.state)
    }

    companion object {
        const val COMMAND = "build-queue"
        const val BUILD_TYPE_ID = "--build-type-id"
        const val BRANCH = "--branch"
        const val COMMENT = "--comment"
        const val PARAMETERS = "--parameters"
    }
}