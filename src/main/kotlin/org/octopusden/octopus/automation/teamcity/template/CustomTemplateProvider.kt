package org.octopusden.octopus.automation.teamcity.template

class CustomTemplateProvider(
    val baseTemplate: String
) : AbstractTemplate() {
    override fun getCompileTemplate(): String {
        return baseTemplate
    }
}