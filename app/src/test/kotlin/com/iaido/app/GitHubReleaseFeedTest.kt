package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitHubReleaseFeedTest {
    @Test
    fun `selects newest timestamped nightly and ignores legacy nightly`() {
        val result = selectAtomRelease(
            """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <title>Iaido Nightly 202609190201</title>
                <updated>2026-09-19T02:01:00Z</updated>
                <link rel="alternate" href="https://github.com/shohamh/Iaido/releases/tag/nightly-202609190201" />
              </entry>
              <entry>
                <title>Legacy nightly</title>
                <updated>2026-09-19T02:02:00Z</updated>
                <link rel="alternate" href="https://github.com/shohamh/Iaido/releases/tag/nightly" />
              </entry>
              <entry>
                <title>Iaido Nightly 202609190159</title>
                <updated>2026-09-19T01:59:00Z</updated>
                <link rel="alternate" href="https://github.com/shohamh/Iaido/releases/tag/nightly-202609190159" />
              </entry>
            </feed>
            """.trimIndent(),
            UpdateChannel.NIGHTLY,
        )

        assertEquals(
            PublicRelease("nightly-202609190201", "Iaido Nightly 202609190201", "2026-09-19T02:01:00Z"),
            result,
        )
        assertEquals(
            "https://github.com/shohamh/Iaido/releases/download/nightly-202609190201/app-release.apk",
            publicReleaseAppRelease(result!!).assets.single().browserDownloadUrl,
        )
    }

    @Test
    fun `does not select stable or legacy releases as a nightly`() {
        val xml = """
            <feed>
              <entry>
                <title>Stable</title>
                <updated>2026-09-19T02:02:00Z</updated>
                <link rel="alternate" href="https://github.com/shohamh/Iaido/releases/tag/v0.1.17" />
              </entry>
              <entry>
                <title>Legacy</title>
                <updated>2026-09-19T02:03:00Z</updated>
                <link rel="alternate" href="https://github.com/shohamh/Iaido/releases/tag/nightly" />
              </entry>
            </feed>
        """.trimIndent()

        assertNull(selectAtomRelease(xml, UpdateChannel.NIGHTLY))
        assertEquals("v0.1.17", selectAtomRelease(xml, UpdateChannel.STABLE)?.tagName)
    }
}
