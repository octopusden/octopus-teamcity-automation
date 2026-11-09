package org.octopusden.octopus.teamcityautomationservice.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import com.fasterxml.jackson.databind.module.SimpleModule
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Older callers still send `teamcity` and `componentsRegistryUrl`. The service takes both from its own
 * configuration, so it skips them and warns, naming the field and never its value (`teamcity` holds a password).
 */
@Component
class LegacyRequestFieldsModule : SimpleModule("LegacyRequestFields") {
    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addDeserializationProblemHandler(Handler)
    }

    private object Handler : DeserializationProblemHandler() {
        override fun handleUnknownProperty(
            ctxt: DeserializationContext,
            p: JsonParser,
            deserializer: JsonDeserializer<*>?,
            beanOrClass: Any?,
            propertyName: String?,
        ): Boolean {
            val target = beanOrClass as? Class<*> ?: beanOrClass?.javaClass
            if (target != CreateBuildChainRequest::class.java || propertyName !in LEGACY_FIELDS) {
                return false
            }
            log.warn(
                "Ignoring '{}' in the request: the service uses the TeamCity and Components Registry it is configured with",
                propertyName,
            )
            p.skipChildren()
            return true
        }
    }

    private companion object {
        val LEGACY_FIELDS = setOf("teamcity", "componentsRegistryUrl")
        val log = LoggerFactory.getLogger(LegacyRequestFieldsModule::class.java)
    }
}
