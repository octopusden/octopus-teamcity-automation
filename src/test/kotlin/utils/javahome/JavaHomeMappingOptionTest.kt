package utils.javahome

import com.github.ajalt.clikt.core.BadParameterValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.octopusden.octopus.automation.teamcity.utils.javahome.JavaHomeMappingOption

class JavaHomeMappingOptionTest {
    @Test
    fun `resolveOrNull resolves against a non-null javaVersion`() {
        val option = JavaHomeMappingOption.parse("env.JDK_{major}_0,11=env.JDK_11_0")

        assertEquals("env.JDK_11_0", option.resolveOrNull("11"))
        assertEquals("env.JDK_17_0", option.resolveOrNull("17"))
    }

    @Test
    fun `resolveOrNull returns null for a null javaVersion`() {
        val option = JavaHomeMappingOption.parse("env.JDK_{major}_0")

        assertEquals(null, option.resolveOrNull(null))
    }

    @Test
    fun `valid mapping with a leading template and overrides is parsed`() {
        val option = JavaHomeMappingOption.parse("env.JDK_{major}_0,8=env.JDK_1_8,11=env.JDK_11_0")

        assertEquals("env.JDK_{major}_0", option.template)
        assertEquals(mapOf(8 to "env.JDK_1_8", 11 to "env.JDK_11_0"), option.overrides)
    }

    @Test
    fun `mapping with only overrides, no leading template, is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("8=env.JDK_1_8,11=env.JDK_11_0")
        }
        assertTrue(ex.message!!.contains("leading"))
    }

    @Test
    fun `a bare entry not in the first position is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("8=env.JDK_1_8,env.JDK_{major}_0")
        }
        assertTrue(ex.message!!.contains("first segment"))
    }

    @Test
    fun `override value without an env prefix is accepted`() {
        val option = JavaHomeMappingOption.parse("env.JDK_{major}_0,8=JDK_1_8")

        assertEquals(mapOf(8 to "JDK_1_8"), option.overrides)
    }

    @Test
    fun `non-numeric override key is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("env.JDK_{major}_0,abc=env.JDK_1_8")
        }
        assertTrue(ex.message!!.contains("abc"))
    }

    @Test
    fun `already-wrapped override value is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("env.JDK_{major}_0,8=%env.JDK_1_8%")
        }
        assertTrue(ex.message!!.contains("8=%env.JDK_1_8%"))
    }

    @Test
    fun `override value containing the placeholder is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("env.JDK_{major}_0,8=env.JDK_{major}_8")
        }
        assertTrue(ex.message!!.contains("8=env.JDK_{major}_8"))
    }

    @Test
    fun `duplicate override key is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("env.JDK_{major}_0,8=env.JDK_1_8,8=env.JDK_8_0")
        }
        assertTrue(ex.message!!.contains("8"))
    }

    @Test
    fun `leading template without the placeholder is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("env.JDK_HOME,8=env.JDK_1_8")
        }
        assertTrue(ex.message!!.contains("env.JDK_HOME"))
    }

    @Test
    fun `leading template with more than one placeholder is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("env.JDK_{major}_{major}")
        }
        assertTrue(ex.message!!.contains("env.JDK_{major}_{major}"))
    }

    @Test
    fun `already-wrapped leading template is rejected`() {
        val ex = assertThrows(BadParameterValue::class.java) {
            JavaHomeMappingOption.parse("%env.JDK_{major}_0%,8=env.JDK_1_8")
        }
        assertTrue(ex.message!!.contains("%env.JDK_{major}_0%"))
    }
}
