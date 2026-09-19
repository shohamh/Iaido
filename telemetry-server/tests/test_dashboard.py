import base64
import dataclasses

import pytest
from fastapi.testclient import TestClient

from iaido_telemetry.config import MIN_DASHBOARD_PASSWORD_LENGTH, Settings

from conftest import build_test_app, envelope, queue_batch_id


OPERATOR_TOKEN = "operator-test-token"
DEFAULT_USERNAME = "iaido"


def basic(username: str, password: str) -> dict[str, str]:
    encoded = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return {"Authorization": f"Basic {encoded}"}


BASIC = basic(DEFAULT_USERNAME, OPERATOR_TOKEN)
WRONG_PASSWORD = basic(DEFAULT_USERNAME, "not-the-token")
WRONG_USERNAME = basic("operator", OPERATOR_TOKEN)


def post_diagnostics(client, installation, headers, sequence: int = 1):
    batch_id = queue_batch_id(sequence)
    response = client.post(
        "/v1/diagnostics/batches",
        headers=headers,
        json={
            "schema_version": 1,
            "batch_id": batch_id,
            "events": [envelope(installation_id=installation["installation_id"])],
        },
    )
    assert response.status_code == 202
    return batch_id


def post_research_text(client, installation, headers, text: str, sequence: int = 2):
    batch_id = queue_batch_id(sequence)
    response = client.post(
        "/v1/research/batches",
        headers=headers,
        json={
            "schema_version": 1,
            "batch_id": batch_id,
            "events": [
                envelope(
                    installation_id=installation["installation_id"],
                    event_id="00000000-0000-0000-0000-0000000000d1",
                    event_type="text_sample",
                    payload={"text": text},
                )
            ],
        },
    )
    assert response.status_code == 202
    return batch_id


def test_dashboard_requires_an_operator_credential(client):
    response = client.get("/")

    assert response.status_code == 401
    assert "basic" in response.headers["www-authenticate"].lower()


def test_dashboard_rejects_wrong_username_or_password(client):
    assert client.get("/", headers=WRONG_PASSWORD).status_code == 401
    assert client.get("/", headers=WRONG_USERNAME).status_code == 401


def test_dashboard_uses_a_configured_login_instead_of_the_operator_token(
    settings, object_root
):
    configured = dataclasses.replace(
        settings, dashboard_username="dash", dashboard_password="correct-horse-battery-staple"
    )
    with TestClient(
        build_test_app(configured, object_root), base_url="https://testserver"
    ) as configured_client:
        assert (
            configured_client.get(
                "/", headers=basic("dash", "correct-horse-battery-staple")
            ).status_code
            == 200
        )
        # The dashboard password replaces the operator token for Basic; the token itself still
        # works as a Bearer credential on the operator API contract.
        assert configured_client.get("/", headers=basic("dash", OPERATOR_TOKEN)).status_code == 401
        assert configured_client.get(
            "/", headers={"Authorization": f"Bearer {OPERATOR_TOKEN}"}
        ).status_code == 200


def test_settings_reject_a_trivially_short_dashboard_password(settings):
    with pytest.raises(ValueError, match="dashboard_password"):
        dataclasses.replace(settings, dashboard_password="short")
    with pytest.raises(ValueError, match="dashboard_username"):
        dataclasses.replace(settings, dashboard_username="")

    assert Settings(
        database_url=settings.database_url, operator_token=OPERATOR_TOKEN
    ).effective_dashboard_password == OPERATOR_TOKEN
    assert MIN_DASHBOARD_PASSWORD_LENGTH > 0


def test_dashboard_lists_both_planes_separately(
    client, installation, diagnostics_headers
):
    diagnostics_batch = post_diagnostics(client, installation, diagnostics_headers)
    research_batch = post_research_text(
        client, installation, diagnostics_headers, "bounded sample"
    )

    response = client.get("/", headers=BASIC)
    body = response.text

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/html")
    assert "<h2>Diagnostics</h2>" in body
    assert "<h2>Research</h2>" in body
    assert diagnostics_batch in body
    assert research_batch in body
    # The diagnostics section never renders research content, and the research batch is linked
    # under its own plane.
    assert f"/batches/research/{installation['installation_id']}/{research_batch}" in body
    assert f"/batches/diagnostics/{installation['installation_id']}/{diagnostics_batch}" in body
    # The overview is metadata only: payload text is rendered on the batch page, not here.
    assert "bounded sample" not in body
    assert "Gesture outcomes" in body
    assert "ACCEPTED" in body
    assert "Audit log" in body


def test_dashboard_escapes_stored_text(client, installation, diagnostics_headers):
    batch_id = post_research_text(
        client,
        installation,
        diagnostics_headers,
        "<script>alert('research')</script>",
    )

    overview = client.get("/", headers=BASIC).text
    detail = client.get(
        f"/batches/research/{installation['installation_id']}/{batch_id}", headers=BASIC
    ).text

    assert "<script>alert('research')</script>" not in detail
    assert "&lt;script&gt;alert(&#x27;research&#x27;)&lt;/script&gt;" in detail
    assert "<script>alert('research')</script>" not in overview


def test_batch_detail_shows_envelopes_and_escapes_payloads(
    client, installation, diagnostics_headers
):
    batch_id = post_research_text(
        client, installation, diagnostics_headers, "<b>spanned</b> text"
    )
    url = f"/batches/research/{installation['installation_id']}/{batch_id}"

    response = client.get(url, headers=BASIC)
    body = response.text

    assert response.status_code == 200
    assert "text_sample" in body
    assert "Object key" in body
    assert "&lt;b&gt;spanned&lt;/b&gt; text" in body
    assert "<b>spanned</b> text" not in body
    assert client.get(url).status_code == 401


def test_batch_detail_is_plane_scoped(client, installation, diagnostics_headers):
    batch_id = post_diagnostics(client, installation, diagnostics_headers)

    wrong_plane = client.get(
        f"/batches/research/{installation['installation_id']}/{batch_id}", headers=BASIC
    )
    unknown_batch = client.get(
        f"/batches/diagnostics/{installation['installation_id']}/{queue_batch_id(9)}",
        headers=BASIC,
    )
    unknown_plane = client.get(
        f"/batches/summary/{installation['installation_id']}/{batch_id}", headers=BASIC
    )

    assert wrong_plane.status_code == 404
    assert unknown_batch.status_code == 404
    assert unknown_plane.status_code == 404


@pytest.mark.parametrize(
    ("header", "expected"),
    [
        ({"Authorization": f"Bearer {OPERATOR_TOKEN}"}, 200),
        ({"Authorization": "Bearer wrong"}, 401),
        ({"Authorization": "Basic bm90LWEtcGFpciJ9"}, 401),
    ],
)
def test_dashboard_accepts_bearer_or_basic_only_with_the_operator_token(
    client, header, expected
):
    assert client.get("/", headers=header).status_code == expected
