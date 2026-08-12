package utils.javahome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.utils.javahome.JavaHomeMapping

class JavaHomeMappingTest {
    @Test
    fun `explicit override for the resolved major wins over the template`() {
        assertEquals(
            "env.JDK_17_CUSTOM",
            JavaHomeMapping.resolve("17", mapOf(17 to "env.JDK_17_CUSTOM"), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `major with no override substitutes into the template`() {
        assertEquals(
            "env.JDK_17_0",
            JavaHomeMapping.resolve("17", mapOf(11 to "env.JDK_11_0"), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `an unlisted future major resolves via the template without a code change`() {
        assertEquals(
            "env.JDK_27_0",
            JavaHomeMapping.resolve("27", mapOf(11 to "env.JDK_11_0"), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `major 8 substitutes into the template when not overridden, no built-in exception`() {
        assertEquals(
            "env.JDK_8_0",
            JavaHomeMapping.resolve("1.8", emptyMap(), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `caller-supplied override reproduces the historical major-8 naming only when supplied`() {
        assertEquals(
            "env.JDK_1_8",
            JavaHomeMapping.resolve("1.8", mapOf(8 to "env.JDK_1_8"), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `an override keyed by the derived major matches both dotted and bare source forms`() {
        val overrides = mapOf(8 to "env.JDK_1_8")
        assertEquals("env.JDK_1_8", JavaHomeMapping.resolve("1.8", overrides, "env.JDK_{major}_0"))
        assertEquals("env.JDK_1_8", JavaHomeMapping.resolve("8", overrides, "env.JDK_{major}_0"))
    }

    @Test
    fun `override value without an env prefix is used as-is`() {
        assertEquals(
            "JDK_1_8",
            JavaHomeMapping.resolve("1.8", mapOf(8 to "JDK_1_8"), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `a modern multi-segment version resolves to its leading segment`() {
        assertEquals(
            "env.JDK_21_0",
            JavaHomeMapping.resolve("21.0.1", emptyMap(), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `a legacy multi-segment version resolves to its second segment`() {
        assertEquals(
            "env.JDK_8_0",
            JavaHomeMapping.resolve("1.8.0_292", emptyMap(), "env.JDK_{major}_0"),
        )
    }

    @Test
    fun `a version with no derivable major resolves to null`() {
        listOf("   ", "<value>", "999999999999999999999", "0", "17-ea").forEach { javaVersion ->
            assertNull(
                JavaHomeMapping.resolve(javaVersion, mapOf(8 to "env.JDK_1_8"), "env.JDK_{major}_0"),
                "expected no major for '$javaVersion'",
            )
        }
    }
}
