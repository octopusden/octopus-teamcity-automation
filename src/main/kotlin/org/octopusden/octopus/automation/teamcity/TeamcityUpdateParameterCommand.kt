package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split

class TeamcityUpdateParameterCommand : CliktCommand(name = "update-parameter") {
    private val name by option("--name", help = "TeamCity parameter name").required()
    private val projectIds by option(
        "--project-ids",
        help = "TeamCity Project Ids (separated by comma/semicolon), optional"
    ).split(SPLIT_SYMBOLS.toRegex())
    private val buildTypeIds by option(
        "--build-type-ids",
        help = "TeamCity Build Configuration Ids (separated by comma/semicolon), optional"
    ).split(SPLIT_SYMBOLS.toRegex())

    private val context by requireObject<MutableMap<String, Any>>()

    override fun run() {
        if (projectIds == null && buildTypeIds == null) {
            throw IllegalArgumentException("Both options --project-ids and --build-type-ids are not defined")
        }
        context[CONFIG] = UpdateParameterConfig(name, projectIds ?: emptyList(), buildTypeIds ?: emptyList())
    }

    companion object {
        data class UpdateParameterConfig(val name: String, val projectIds: List<String>, val buildTypeIds: List<String>)

        const val CONFIG = "config"
    }
}