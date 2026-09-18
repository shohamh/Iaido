package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitHubReleaseMonitorClientTest {
    @Test
    fun `parses release identity and version label`() {
        val result = parseReleaseProbe(
            """
            {
              "id": 42,
              "tag_name": "nightly",
              "name": "Iaido Nightly 18",
              "assets": [
                {
                  "id": 9,
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/shohamh/Iaido/releases/download/nightly/app-release.apk",
                  "size": 123,
                  "updated_at": "2026-09-18T18:30:00Z"
                }
              ]
            }
            """.trimIndent(),
            UpdateChannel.NIGHTLY,
        )

        assertEquals(
            ReleaseProbe.Available(
                ReleaseIdentity(UpdateChannel.NIGHTLY, "nightly", 42L, "2026-09-18T18:30:00Z"),
                "Iaido Nightly 18",
            ),
            result,
        )
    }

    @Test
    fun `parses queued and running release workflows`() {
        val result = parseReleaseWorkflowStatus(
            """
            {
              "workflow_runs": [
                {"id": 3, "status": "completed", "name": "Android release"},
                {"id": 7, "status": "in_progress", "name": "Android release"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(ReleaseWorkflowStatus.Running(7L), result)
    }

    @Test
    fun `empty workflow list is idle`() {
        assertEquals(ReleaseWorkflowStatus.Idle, parseReleaseWorkflowStatus("{\"workflow_runs\": []}"))
    }
}
