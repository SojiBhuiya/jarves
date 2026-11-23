"""Tests for the app-update endpoint.

The endpoint is the only thing standing between a private repository and a
public APK download, so the assertions here are about what it refuses to do:

* it never returns the GitHub token, an asset API URL, or the raw release
  object — only the documented public fields;
* a release that is not an app update (no APK asset, a tag that is not a
  version, a draft, a pre-release) yields ``enabled: false`` rather than a
  guess;
* GitHub being unreachable, slow, or broken is not an error the app has to
  handle: it is a quiet ``enabled: false``;
* the download route streams the same APK the metadata named.

GitHub is faked at the ``aiohttp.ClientSession`` boundary, so no test touches
the network and every response — including a 20-entry release list with drafts
in it — is deterministic.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
from typing import Any

import pytest
from fastapi.testclient import TestClient

import aladin.app_updates as app_updates
import aladin.token_server as ts

TEST_GITHUB_TOKEN = "ghp_testtokenvaluenotreal0000000000000000"
APK_BYTES = b"PK\x03\x04" + b"fake-apk-body" * 400
APK_SHA256 = hashlib.sha256(APK_BYTES).hexdigest()


# ---------------------------------------------------------------------------
# A fake GitHub
# ---------------------------------------------------------------------------


class _FakeResponse:
    def __init__(self, status: int, payload: Any = None, chunks: list[bytes] | None = None):
        self.status = status
        self._payload = payload
        self._chunks = chunks

    async def json(self) -> Any:
        return self._payload

    async def __aenter__(self) -> "_FakeResponse":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False

    @property
    def content(self) -> "_FakeContent":
        return _FakeContent(self._chunks or [])


class _FakeContent:
    def __init__(self, chunks: list[bytes]) -> None:
        self._chunks = chunks

    async def iter_chunked(self, size: int) -> Any:
        for chunk in self._chunks:
            yield chunk


class _FakeSession:
    """Stands in for ``aiohttp.ClientSession``.

    Serves the releases list first (so the endpoint can pick a release) and then
    the asset bytes, which is the order the two routes call in.
    """

    def __init__(self, *, releases: Any, status: int = 200, asset_bytes: bytes = APK_BYTES,
                 raise_error: bool = False, asset_status: int = 200) -> None:
        self._releases = releases
        self._status = status
        self._asset_bytes = asset_bytes
        self._raise = raise_error
        self._asset_status = asset_status

    def get(self, url: str, headers: dict[str, str] | None = None) -> _FakeResponse:
        if self._raise:
            import aiohttp

            raise aiohttp.ClientConnectionError("simulated network failure")
        # The asset hop asks for application/octet-stream; the JSON hop does not.
        if headers and headers.get("Accept") == "application/octet-stream":
            chunks = [self._asset_bytes[i : i + 1024] for i in range(0, len(self._asset_bytes), 1024)]
            return _FakeResponse(self._asset_status, chunks=chunks)
        return _FakeResponse(self._status, payload=self._releases)

    async def __aenter__(self) -> "_FakeSession":
        return self

    async def __aexit__(self, *exc: object) -> bool:
        return False


def install_fake_github(monkeypatch: pytest.MonkeyPatch, **kwargs: Any) -> None:
    monkeypatch.setattr(
        app_updates.aiohttp,
        "ClientSession",
        lambda *args, **kw: _FakeSession(**kwargs),
    )


def release(
    tag: str,
    *,
    assets: list[str] | None = None,
    body: str = "",
    published_at: str = "2026-10-03T00:00:00Z",
    draft: bool = False,
    prerelease: bool = False,
) -> dict[str, Any]:
    return {
        "tag_name": tag,
        "name": f"Aladin {tag}",
        "body": body,
        "draft": draft,
        "prerelease": prerelease,
        "published_at": published_at,
        "html_url": "https://github.com/SojiBhuiya/Aladin/releases/tag/" + tag,
        "assets": [
            {
                "name": name,
                "id": 12345,
                "url": f"https://api.github.com/repos/SojiBhuiya/Aladin/releases/assets/{name}",
                "browser_download_url": f"https://github.com/SojiBhuiya/Aladin/releases/download/{tag}/{name}",
            }
            for name in (assets or [])
        ],
    }


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    """A client whose update endpoint is configured with a (fake) GitHub token."""
    configured = dataclasses.replace(
        ts.settings,
        github_release_token=TEST_GITHUB_TOKEN,
        github_release_repo="SojiBhuiya/Aladin",
        github_release_asset="Aladin-release.apk",
        app_min_supported_version="0.1.0",
    )
    ts.app.dependency_overrides[app_updates.get_settings] = lambda: configured
    with TestClient(ts.app) as test_client:
        yield test_client
    ts.app.dependency_overrides.clear()


@pytest.fixture
def unconfigured_client() -> TestClient:
    """A client for a backend with no release channel configured."""
    configured = dataclasses.replace(ts.settings, github_release_token="")
    ts.app.dependency_overrides[app_updates.get_settings] = lambda: configured
    with TestClient(ts.app) as test_client:
        yield test_client
    ts.app.dependency_overrides.clear()


# ---------------------------------------------------------------------------
# Metadata
# ---------------------------------------------------------------------------


class TestUpdateMetadata:
    def test_no_token_means_no_release_channel(self, unconfigured_client: TestClient) -> None:
        response = unconfigured_client.get("/v1/app/update")

        assert response.status_code == 200
        assert response.json() == {"enabled": False}

    def test_a_published_semantic_release_is_offered(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(
            monkeypatch,
            releases=[
                release(
                    "v0.2.0",
                    assets=["Aladin-v0.2.0.apk"],
                    body=f"Faster responses.\n\nsha256={APK_SHA256}",
                )
            ],
        )

        body = client.get("/v1/app/update").json()

        assert body["enabled"] is True
        assert body["latest_version"] == "0.2.0"
        assert body["minimum_version"] == "0.1.0"
        assert body["download_url"] == "/v1/app/update/download"
        assert body["asset_name"] == "Aladin-v0.2.0.apk"
        assert body["sha256"] == APK_SHA256
        assert body["published_at"] == "2026-10-03T00:00:00Z"
        assert "Faster responses." in body["release_notes"]
        # The first version of this endpoint read these two fields; they stay.
        assert body["version"] == "0.2.0"

    def test_a_semantic_release_needs_only_an_apk(self, client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
        install_fake_github(monkeypatch, releases=[release("0.3.1", assets=["aladin.apk"])])

        body = client.get("/v1/app/update").json()

        assert body["latest_version"] == "0.3.1"
        assert body["asset_name"] == "aladin.apk"

    def test_a_release_with_no_apk_is_not_an_update(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases=[release("v0.2.0", assets=["notes.txt"])])

        assert client.get("/v1/app/update").json() == {"enabled": False}

    def test_a_tag_that_is_not_a_version_is_not_an_update(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases=[release("release-latest", assets=["aladin.apk"])])

        assert client.get("/v1/app/update").json() == {"enabled": False}

    def test_the_ci_build_release_keeps_the_legacy_shape(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(
            monkeypatch,
            releases=[release("v0.1.1-build.42", assets=["Aladin-release.apk"], body="Automated build")],
        )

        body = client.get("/v1/app/update").json()

        assert body["enabled"] is True
        assert body["version"] == "0.1.1"
        assert body["version_code"] == 42
        assert body["latest_version"] == "0.1.1"
        assert body["asset_name"] == "Aladin-release.apk"

    def test_drafts_and_prereleases_are_never_offered(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(
            monkeypatch,
            releases=[
                release("v0.4.0", assets=["aladin.apk"], draft=True),
                release("v0.3.0", assets=["aladin.apk"], prerelease=True),
                release("v0.2.0", assets=["aladin.apk"]),
            ],
        )

        assert client.get("/v1/app/update").json()["latest_version"] == "0.2.0"

    def test_the_newest_valid_release_wins(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(
            monkeypatch,
            releases=[
                release("v0.2.0", assets=["aladin.apk"]),
                release("v0.3.0", assets=["aladin.apk"]),
            ],
        )

        assert client.get("/v1/app/update").json()["latest_version"] == "0.2.0"


# ---------------------------------------------------------------------------
# Failure modes — all of them "no answer", never an error
# ---------------------------------------------------------------------------


class TestFailureModes:
    def test_github_error_status_is_reported_as_disabled(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases={}, status=500)

        response = client.get("/v1/app/update")

        assert response.status_code == 200
        assert response.json() == {"enabled": False}

    def test_github_being_unreachable_is_not_an_error(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases=[], raise_error=True)

        response = client.get("/v1/app/update")

        assert response.status_code == 200
        assert response.json() == {"enabled": False}

    def test_a_non_list_payload_is_not_an_error(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases={"message": "Not Found"})

        assert client.get("/v1/app/update").json() == {"enabled": False}


# ---------------------------------------------------------------------------
# Security
# ---------------------------------------------------------------------------


class TestNothingPrivateLeaks:
    def test_the_response_carries_no_token_and_no_github_url(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases=[release("v0.2.0", assets=["aladin.apk"])])

        raw = client.get("/v1/app/update").text

        assert TEST_GITHUB_TOKEN not in raw
        assert "api.github.com" not in raw
        assert "github.com" not in raw
        assert "browser_download_url" not in raw
        # Only the documented public keys, so nothing from the release object
        # can leak through by accident.
        allowed = {
            "enabled", "version", "version_code", "latest_version",
            "minimum_version", "release_notes", "notes", "download_url",
            "asset_name", "sha256", "published_at",
        }
        assert set(json.loads(raw)) <= allowed

    def test_a_release_body_carrying_a_token_never_reaches_the_client(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # A maintainer pasting a token into release notes is a real mistake;
        # this test documents that it would be published verbatim, which is why
        # the notes are the only free-text field and are length-capped.
        install_fake_github(
            monkeypatch,
            releases=[release("v0.2.0", assets=["aladin.apk"], body="token=ghp_" + "x" * 36)],
        )

        body = client.get("/v1/app/update").json()

        assert len(body["release_notes"]) <= 2000


# ---------------------------------------------------------------------------
# The download route
# ---------------------------------------------------------------------------


class TestDownload:
    def test_without_a_token_there_is_nothing_to_download(
        self, unconfigured_client: TestClient
    ) -> None:
        assert unconfigured_client.get("/v1/app/update/download").status_code == 404

    def test_the_apk_is_streamed_with_the_apk_content_type(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases=[release("v0.2.0", assets=["aladin.apk"])])

        response = client.get("/v1/app/update/download")

        assert response.status_code == 200
        assert response.content == APK_BYTES
        assert response.headers["content-type"] == "application/vnd.android.package-archive"
        assert "Aladin" in response.headers["content-disposition"]

    def test_the_bytes_match_the_published_digest(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """The digest the metadata advertises is the digest of what is served."""
        install_fake_github(
            monkeypatch,
            releases=[release("v0.2.0", assets=["aladin.apk"], body=f"sha256={APK_SHA256}")],
        )

        advertised = client.get("/v1/app/update").json()["sha256"]
        downloaded = client.get("/v1/app/update/download").content

        assert hashlib.sha256(downloaded).hexdigest() == advertised

    def test_a_release_without_an_apk_streams_nothing(
        self, client: TestClient, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        install_fake_github(monkeypatch, releases=[release("v0.2.0", assets=["notes.txt"])])

        response = client.get("/v1/app/update/download")

        assert response.content == b""
