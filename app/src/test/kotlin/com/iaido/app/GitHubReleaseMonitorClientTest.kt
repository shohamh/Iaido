package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitHubReleaseMonitorClientTest {
    @Test
    fun `selects newest timestamped nightly release`() {
        val result = parseReleaseProbe(
            selectReleaseJson(
                """
                [
                  {
                    "id": 41,
                    "tag_name": "nightly-202609190159",
                    "name": "Iaido Nightly 202609190159",
                    "prerelease": true,
                    "draft": false,
                    "assets": [
                      {
                        "id": 8,
                        "name": "app-release.apk",
                        "browser_download_url": "https://github.com/shohamh/Iaido/releases/download/nightly-202609190159/app-release.apk",
                        "size": 123,
                        "updated_at": "2026-09-19T01:59:00Z"
                      }
                    ]
                  },
                  {
                    "id": 42,
                    "tag_name": "nightly-202609190201",
                    "name": "Iaido Nightly 202609190201",
                    "prerelease": true,
                    "draft": false,
                    "assets": [
                      {
                        "id": 9,
                        "name": "app-release.apk",
                        "browser_download_url": "https://github.com/shohamh/Iaido/releases/download/nightly-202609190201/app-release.apk",
                        "size": 123,
                        "updated_at": "2026-09-19T02:01:00Z"
                      }
                    ]
                  },
                  {
                    "id": 43,
                    "tag_name": "nightly",
                    "name": "Legacy nightly",
                    "prerelease": true,
                    "draft": false,
                    "assets": [
                      {
                        "id": 10,
                        "name": "app-release.apk",
                        "browser_download_url": "https://github.com/shohamh/Iaido/releases/download/nightly/app-release.apk",
                        "size": 123,
                        "updated_at": "2026-09-19T02:02:00Z"
                      }
                    ]
                  }
                ]
                """.trimIndent(),
                UpdateChannel.NIGHTLY,
            )!!,
            UpdateChannel.NIGHTLY,
        )

        assertEquals(
            ReleaseProbe.Available(
                ReleaseIdentity(
                    UpdateChannel.NIGHTLY,
                    "nightly-202609190201",
                    42L,
                    "2026-09-19T02:01:00Z",
                ),
                "Iaido Nightly 202609190201",
            ),
            result,
        )
    }

    @Test
    fun `ignores legacy nightly release`() {
        assertEquals(
            null,
            selectReleaseJson(
                """
                [{
                  "id": 42,
                  "tag_name": "nightly",
                  "name": "Legacy nightly",
                  "prerelease": true,
                  "draft": false,
                  "assets": []
                }]
                """.trimIndent(),
                UpdateChannel.NIGHTLY,
            ),
        )
    }

    @Test
    fun `parses release identity and version label`() {
        val result = parseReleaseProbe(
            """
            {
              "id": 42,
              "tag_name": "nightly-202609190201",
              "name": "Iaido Nightly 202609190201",
              "assets": [
                {
                  "id": 9,
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/shohamh/Iaido/releases/download/nightly-202609190201/app-release.apk",
                  "size": 123,
                  "updated_at": "2026-09-19T02:01:00Z"
                }
              ]
            }
            """.trimIndent(),
            UpdateChannel.NIGHTLY,
        )

        assertEquals(
            ReleaseProbe.Available(
                ReleaseIdentity(UpdateChannel.NIGHTLY, "nightly-202609190201", 42L, "2026-09-19T02:01:00Z"),
                "Iaido Nightly 202609190201",
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
