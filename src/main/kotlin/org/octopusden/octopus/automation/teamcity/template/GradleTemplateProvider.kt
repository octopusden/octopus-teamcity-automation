package org.octopusden.octopus.automation.teamcity.template

class GradleTemplateProvider : AbstractTemplate() {
    override fun getCompileTemplate(): String {
        return "CDCompileUTGradle"
    }
}