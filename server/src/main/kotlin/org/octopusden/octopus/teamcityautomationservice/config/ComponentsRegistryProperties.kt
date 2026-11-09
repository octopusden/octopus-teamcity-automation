package org.octopusden.octopus.teamcityautomationservice.config

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * The one Components Registry the service reads components from. A blank value stops the startup with an
 * error that names the property. Keys the shared service config defines under the same prefix for other
 * services (e.g. `components-registry.vcs...`) are ignored.
 */
@Validated
@ConfigurationProperties("components-registry")
data class ComponentsRegistryProperties(
    /** Base URL of the Components Registry. */
    @field:NotBlank(message = "components-registry.url must not be blank")
    val url: String = "",
) {
    @get:AssertTrue(message = "components-registry.url $URL_MESSAGE")
    val isUrlValid: Boolean get() = url.isBlank() || URL_REGEX.matches(url)
}
