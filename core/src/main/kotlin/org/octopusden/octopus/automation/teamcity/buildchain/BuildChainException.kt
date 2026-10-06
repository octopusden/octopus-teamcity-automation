package org.octopusden.octopus.automation.teamcity.buildchain

import org.octopusden.octopus.components.registry.core.dto.BuildSystem

/** A component the generator cannot build a chain for. Thrown before any TeamCity object is created. */
sealed class BuildChainException(
    message: String,
) : RuntimeException(message)

/** The component's build system has no compile template. */
class UnsupportedBuildSystemException(
    val buildSystem: BuildSystem,
) : BuildChainException("Unsupported build system: ${buildSystem.name}")

/** A registry VCS root is not Git. */
class UnsupportedVcsTypeException(
    message: String,
) : BuildChainException(message)

/** The registry VCS roots cannot be placed: repeated repository, several roots at the checkout root or a reserved Checkout Directory. */
class UnsupportedVcsRootLayoutException(
    message: String,
) : BuildChainException(message)
