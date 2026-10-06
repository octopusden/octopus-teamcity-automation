package org.octopusden.octopus.automation.teamcity

import org.octopusden.octopus.infrastructure.teamcity.client.TeamcityClient
import java.lang.reflect.Proxy

/** A [TeamcityClient] that records every call and answers through [answer]; unanswered calls return null. */
class RecordingTeamcityClient(
    private val answer: (method: String, args: List<Any?>) -> Any? = { _, _ -> null },
) {
    data class Call(
        val method: String,
        val args: List<Any?>,
    )

    val calls = mutableListOf<Call>()

    val client: TeamcityClient =
        Proxy.newProxyInstance(TeamcityClient::class.java.classLoader, arrayOf(TeamcityClient::class.java)) { _, method, args ->
            val argList = args?.toList() ?: emptyList()
            calls += Call(method.name, argList)
            answer(method.name, argList)
        } as TeamcityClient

    fun callsOf(method: String) = calls.filter { it.method == method }
}
