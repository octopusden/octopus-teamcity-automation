import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.octopusden.octopus.automation.teamcity.parseOwnerRepo
import java.util.stream.Stream

class GitUrlsTest {

    @ParameterizedTest
    @MethodSource("gitUrls")
    fun testParseOwnerRepo(url: String, expectedOwner: String, expectedRepo: String) {
        val (owner, repo) = parseOwnerRepo(url)
        assertEquals(expectedOwner, owner)
        assertEquals(expectedRepo, repo)
    }

    @ParameterizedTest
    @MethodSource("invalidGitUrls")
    fun testParseOwnerRepoRejectsInvalid(url: String) {
        assertThrows(IllegalArgumentException::class.java) { parseOwnerRepo(url) }
    }

    companion object {
        @JvmStatic
        fun gitUrls(): Stream<Arguments> = Stream.of(
            Arguments.of("https://github.com/octopusden/octopus-teamcity-automation.git", "octopusden", "octopus-teamcity-automation"),
            Arguments.of("https://github.com/octopusden/octopus-teamcity-automation", "octopusden", "octopus-teamcity-automation"),
            Arguments.of("git@github.com:octopusden/octopus-teamcity-automation.git", "octopusden", "octopus-teamcity-automation"),
            Arguments.of("ssh://git@git.domain.corp/octopusden/some-repo.git", "octopusden", "some-repo"),
            // repository names are case-sensitive and must be preserved
            Arguments.of("https://github.com/Octopusden/MixedCase-Repo.git", "Octopusden", "MixedCase-Repo"),
            // deeper paths (e.g. GitLab subgroups) fall back to the last two segments
            Arguments.of("https://git.domain.corp/group/subgroup/repo.git", "subgroup", "repo"),
        )

        @JvmStatic
        fun invalidGitUrls(): Stream<Arguments> = Stream.of(
            Arguments.of("https://github.com/repo.git"),
            Arguments.of("git@github.com:repo.git"),
        )
    }
}
