package org.octopusden.octopus.automation.teamcity

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainConfig
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainCreator
import org.octopusden.octopus.automation.teamcity.buildchain.BuildChainRequest
import org.octopusden.octopus.automation.teamcity.buildchain.UnsupportedBuildSystemException
import org.octopusden.octopus.automation.teamcity.buildchain.UnsupportedVcsRootLayoutException
import org.octopusden.octopus.automation.teamcity.buildchain.UnsupportedVcsTypeException
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClient
import org.octopusden.octopus.components.registry.client.impl.ClassicComponentsRegistryServiceClientUrlProvider
import org.octopusden.octopus.components.registry.core.exceptions.NotFoundException
import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient

class TeamcityCreateBuildChainCommand : CliktCommand(name = COMMAND) {
    private val context by requireObject<MutableMap<String, Any>>()

    private val parentProjectId by option(PARENT, help = "Teamcity parent project Id")
        .convert { it.trim() }
        .required()
        .check("$PARENT is empty") { it.isNotEmpty() }

    private val componentName by option(COMPONENT, help = "Component registry name")
        .convert { it.trim() }
        .required()
        .check("$COMPONENT is empty") { it.isNotEmpty() }

    private val minorVersion by option(VERSION, help = "Minor version")
        .convert { it.trim() }
        .required()
        .check("$VERSION is empty") { it.isNotEmpty() }

    private val componentsRegistryUrl by option(CR, help = "Components Registry service Url")
        .required()
        .check("$CR is empty") { it.isNotEmpty() }

    private val createChecklist by option(CREATE_CHECKLIST, help = "Generate check list validation")
        .convert { it.trim().toBoolean() }
        .default(true)

    private val createRcForce by option(CREATE_RC_FORCE, help = "Force generate RC for non EE components")
        .convert { it.trim().toBoolean() }
        .default(false)

    private val gradleCompileTemplate by option(GRADLE_COMPILE_TEMPLATE, help = "Compile template id, Gradle family")
        .convert { it.trim() }
        .default(BuildChainConfig.DEFAULT_GRADLE_COMPILE_TEMPLATE)
        .check("$GRADLE_COMPILE_TEMPLATE is empty") { it.isNotEmpty() }

    private val mavenCompileTemplate by option(MAVEN_COMPILE_TEMPLATE, help = "Compile template id, Maven")
        .convert { it.trim() }
        .default(BuildChainConfig.DEFAULT_MAVEN_COMPILE_TEMPLATE)
        .check("$MAVEN_COMPILE_TEMPLATE is empty") { it.isNotEmpty() }

    private val client by lazy { context[TeamcityCommand.CLIENT] as TeamcityClient }

    // The library exception is not chained: a "Caused by" block would change stderr.
    @Suppress("SwallowedException")
    override fun run() {
        val componentsRegistryClient = ClassicComponentsRegistryServiceClient(
            object : ClassicComponentsRegistryServiceClientUrlProvider {
                override fun getApiUrl(): String = componentsRegistryUrl
            },
        )
        val request = BuildChainRequest(
            parentProjectId = parentProjectId,
            componentName = componentName,
            minorVersion = minorVersion,
            createChecklist = createChecklist,
            createRcForce = createRcForce,
        )
        // Rethrown as the exceptions this command raised before the library existed, so its exit
        // code and stderr stay the same.
        try {
            val config = BuildChainConfig(
                gradleCompileTemplate = gradleCompileTemplate,
                mavenCompileTemplate = mavenCompileTemplate,
            )
            BuildChainCreator(client, componentsRegistryClient, config).create(request)
        } catch (e: UnsupportedBuildSystemException) {
            throw NotFoundException(e.message.orEmpty())
        } catch (e: UnsupportedVcsTypeException) {
            throw UnsupportedOperationException(e.message)
        } catch (e: UnsupportedVcsRootLayoutException) {
            throw UnsupportedOperationException(e.message)
        }
    }

    companion object {
        const val COMMAND = "create-build-chain"
        const val PARENT = "--parent-project-id"
        const val COMPONENT = "--component"
        const val VERSION = "--minor-version"
        const val CR = "--registry-url"
        const val CREATE_CHECKLIST = "--create-checklist"
        const val CREATE_RC_FORCE = "--create-rc-force"
        const val GRADLE_COMPILE_TEMPLATE = "--gradle-compile-template"
        const val MAVEN_COMPILE_TEMPLATE = "--maven-compile-template"

        const val TEMPLATE_GRADLE_COMPILE = BuildChainConfig.DEFAULT_GRADLE_COMPILE_TEMPLATE
        const val TEMPLATE_MAVEN_COMPILE = BuildChainConfig.DEFAULT_MAVEN_COMPILE_TEMPLATE
        const val TEMPLATE_RC = BuildChainConfig.DEFAULT_RC_TEMPLATE
        const val TEMPLATE_CHECKLIST = BuildChainConfig.DEFAULT_CHECKLIST_TEMPLATE
        const val TEMPLATE_RELEASE = BuildChainConfig.DEFAULT_RELEASE_TEMPLATE
    }
}
