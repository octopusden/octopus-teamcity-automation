package org.octopusden.octopus.automation.teamcity.template

abstract class AbstractTemplate : TemplateProvider {

    override fun getRCTemplate(): String {
        return "CdReleaseCandidateNew"
    }

    override fun getChecklistTemplate(): String {
        return "CdReleaeChecklistValidation"
    }

    override fun getReleaseTemplate(): String {
        return "CDRelease"
    }
}