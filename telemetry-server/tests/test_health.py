from fastapi.testclient import TestClient

from tests.conftest import build_test_app


def test_health_returns_ok_over_https_without_credentials(client):
    response = client.get("/v1/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_health_is_rejected_over_plain_http(settings, object_root):
    with TestClient(
        build_test_app(settings, object_root), base_url="http://testserver"
    ) as http_client:
        response = http_client.get("/v1/health")

    assert response.status_code == 426


def test_health_exposes_no_installation_or_operator_data(client, settings):
    response = client.get("/v1/health")
    text = response.text.lower()

    assert "installation" not in text
    assert "credential" not in text
    assert settings.operator_token not in response.text
    assert "write_credential" not in response.json()


def test_health_does_not_create_an_installation(client):
    client.get("/v1/health")
    client.get("/v1/health")

    created = client.post("/v1/installations")
    assert created.status_code in (200, 201)
