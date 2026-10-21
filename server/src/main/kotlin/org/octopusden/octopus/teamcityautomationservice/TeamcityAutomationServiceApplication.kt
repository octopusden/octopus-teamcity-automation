package org.octopusden.octopus.teamcityautomationservice

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication
@ConfigurationPropertiesScan
class TeamcityAutomationServiceApplication

fun main(args: Array<String>) {
    SpringApplication.run(TeamcityAutomationServiceApplication::class.java, *args)
}
