package org.octopusden.octopus.automation.teamcity.template

class MavenTemplateProvider : AbstractTemplate() {
    override fun getCompileTemplate(): String {
        return "CDCompileUTMaven"
    }
}