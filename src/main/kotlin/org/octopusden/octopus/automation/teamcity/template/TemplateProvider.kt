package org.octopusden.octopus.automation.teamcity.template

interface TemplateProvider {
    fun getCompileTemplate(): String

    fun getRCTemplate(): String

    fun getChecklistTemplate(): String

    fun getReleaseTemplate(): String
}