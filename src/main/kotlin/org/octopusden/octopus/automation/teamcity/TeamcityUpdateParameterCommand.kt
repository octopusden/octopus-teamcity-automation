package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class TeamcityUpdateParameterCommand : CliktCommand(name = "update-parameter") {
    private val name by option("--$NAME", help = "TeamCity parameter name").required()

    private val context by requireObject<MutableMap<String, Any>>()

    override fun run() {
        context[NAME] = name
    }

    companion object {
        const val NAME = "name"
    }
}