package org.octopusden.octopus.teamcityautomationservice.service.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaseUrlTest {
    @Test
    fun containsUrlsUnderItsPath() {
        val base = BaseUrl.parse("http://Host:8111/tc/")!!

        assertTrue(base.contains("http://host:8111/tc"))
        assertTrue(base.contains("HTTP://host:8111/tc/app/rest/projects?locator=id:P"))
        assertFalse(base.contains("http://host:8111/tcx/app/rest"))
        assertFalse(base.contains("http://host:8112/tc"))
        assertFalse(base.contains("https://host:8111/tc"))
        assertFalse(base.contains("http://host.evil:8111/tc"))
        assertEquals(1, base.depth)
    }

    @Test
    fun treatsTheDefaultPortAsExplicit() {
        assertTrue(BaseUrl.parse("https://host")!!.contains("https://host:443/path"))
        assertTrue(BaseUrl.parse("http://host:80")!!.contains("http://host/path"))
    }

    @Test
    fun doesNotLetDotSegmentsEscapeThePath() {
        assertFalse(BaseUrl.parse("http://host/tc")!!.contains("http://host/tc/../admin"))
    }

    @Test
    fun rejectsWhatIsNotAnAbsoluteUrl() {
        assertNull(BaseUrl.parse("host:8111"))
        assertNull(BaseUrl.parse("http://bad host"))
        assertFalse(BaseUrl.parse("http://host")!!.contains("not a url"))
    }
}
