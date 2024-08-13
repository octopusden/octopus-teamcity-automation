package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class TeamcityUpdateParameterCommand : CliktCommand(name = COMMAND) {
    private val name by option(NAME_OPTION, help = "TeamCity parameter name").convert { it.trim() }.required()
        .check("$NAME_OPTION is empty") { it.isNotEmpty() }
    private val projectIds by option(
        PROJECT_IDS_OPTION, help = "TeamCity project ids (separated by comma/semicolon), optional"
    ).convert { projectIdsValue ->
        projectIdsValue.split(SPLIT_SYMBOLS.toRegex()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.default(emptySet())
    private val buildTypeIds by option(
        BUILD_TYPE_IDS_OPTION, help = "TeamCity build configuration ids (separated by comma/semicolon), optional"
    ).convert { buildTypeIdsValue ->
        buildTypeIdsValue.split(SPLIT_SYMBOLS.toRegex()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.default(emptySet())

    private val context by requireObject<MutableMap<String, Any>>()

    override fun run() {
        if (projectIds.isEmpty() && buildTypeIds.isEmpty()) {
            throw IllegalArgumentException("Both options --project-ids and --build-type-ids are empty")
        }
        context[CONFIG] = UpdateParameterConfig(name, projectIds, buildTypeIds)
    }

    companion object {
        const val COMMAND = "update-parameter"
        const val NAME_OPTION = "--name"
        const val PROJECT_IDS_OPTION = "--project-ids"
        const val BUILD_TYPE_IDS_OPTION = "--build-type-ids"
        const val CONFIG = "config"

        data class UpdateParameterConfig(val name: String, val projectIds: Set<String>, val buildTypeIds: Set<String>)
    }
}