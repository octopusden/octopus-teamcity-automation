package org.octopusden.octopus.teamcityautomationservice.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler
import com.fasterxml.jackson.databind.module.SimpleModule
import org.octopusden.octopus.teamcityautomationservice.client.common.dto.CreateBuildChainRequest
import org.springframework.stereotype.Component

/**
 * Older callers send `teamcity` and `componentsRegistryUrl`. The service takes both from its own configuration,
 * so a request that still carries one is rejected rather than served on a different server or account.
 * The rejection names the field and never its value (`teamcity` holds a password).
 */
@Component
class RemovedRequestFieldsModule : SimpleModule("RemovedRequestFields") {
    override fun setupModule(context: SetupContext) {
        super.setupModule(context)
        context.addDeserializationProblemHandler(Handler)
    }

    /** A removed field was found in the request body. Its message is safe to return to the caller. */
    class RemovedFieldException(
        p: JsonParser,
        val field: String,
    ) : JsonMappingException(p, "Removed request field: $field") {
        fun clientMessage(): String =
            when (field) {
                COMPONENTS_REGISTRY_URL ->
                    "$field: no longer accepted; the service uses its own configured Components Registry"
                else -> "$field: no longer accepted; the service uses its own configured TeamCity server and account"
            }
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
            if (target != CreateBuildChainRequest::class.java || propertyName !in REMOVED_FIELDS) {
                return false
            }
            throw RemovedFieldException(p, propertyName!!)
        }
    }

    private companion object {
        const val TEAMCITY = "teamcity"
        const val COMPONENTS_REGISTRY_URL = "componentsRegistryUrl"
        val REMOVED_FIELDS = setOf(TEAMCITY, COMPONENTS_REGISTRY_URL)
    }
}
