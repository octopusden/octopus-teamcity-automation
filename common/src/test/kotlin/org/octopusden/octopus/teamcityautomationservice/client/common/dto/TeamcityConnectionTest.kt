package org.octopusden.octopus.teamcityautomationservice.client.common.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

class TeamcityConnectionTest {
    @Test
    fun masksThePasswordWhereverTheRequestIsPrinted() {
        val password = UUID.randomUUID().toString()
        val request = CreateBuildChainRequest(
            TeamcityConnection("http://teamcity:8111", "user", password),
            "http://registry:4567",
            "Parent",
            "component",
            "1.0",
        )

        assertFalse(request.toString().contains(password))
        assertEquals("TeamcityConnection(url=http://teamcity:8111, user=user, password=***)", request.teamcity.toString())
    }
}
