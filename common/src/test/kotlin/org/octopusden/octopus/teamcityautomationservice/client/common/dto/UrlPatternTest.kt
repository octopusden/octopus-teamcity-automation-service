package org.octopusden.octopus.teamcityautomationservice.client.common.dto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UrlPatternTest {
    private val pattern = Regex(URL_PATTERN)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://teamcity:8111",
            "https://teamcity.example",
            "https://teamcity.example/",
            "https://components-registry.example:4567/path?query=1#fragment",
            "http://[::1]:8111",
        ],
    )
    fun acceptsPlainUrls(url: String) {
        assertTrue(pattern.matches(url), url)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://user:password@teamcity.example",
            "https://user@teamcity.example",
            "https://:password@teamcity.example/path",
            "teamcity.example",
            "ftp://teamcity.example",
            "https://",
            "https://teamcity example",
        ],
    )
    fun rejectsUrlsWithUserInfoOrNotHttp(url: String) {
        assertFalse(pattern.matches(url), url)
    }
}
