"""Update metadata for the Android app.

Why this endpoint exists
------------------------
The app must never talk to GitHub directly. The repository is private, so a
direct call would need a token inside the APK — and a token inside an APK belongs
to anyone who has the APK. Instead the *backend* holds the token (as a server-side
secret), reads the release list, and answers with public metadata only. The APK
itself is streamed back through :func:`download_app_update`, so the device never
contacts GitHub and the token never leaves the server.

What counts as an update
------------------------
A **published GitHub Release**, not a commit and not a tag. A push to a branch
creates neither, so ordinary development is invisible to users. Two shapes are
accepted, in this order:

1. A semantic release: tag ``v0.2.0`` (or ``0.2.0``), any asset name. This is
   what a user-facing update should look like.
2. The CI build release: tag ``v0.1.1-build.42`` with an asset literally named
   ``Aladin-release.apk``. Older clients of this endpoint read
   ``version``/``version_code`` and are kept working by publishing both field
   sets.

If neither shape matches, the endpoint reports ``enabled: false`` rather than
guessing. ``latest_version`` is a semantic version, which the app compares
numerically (never as a string: ``0.1.10 > 0.1.9``).
"""

import re

import aiohttp
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse

from .config import Settings

router = APIRouter()

# A semantic version, with an optional leading v: v1.2.3 / 0.2.0 / 1.0.0-rc.1
_SEMVER = re.compile(r'^v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$')
# The CI build shape: v0.1.1-build.42
_BUILD_TAG = re.compile(r'^v?(?P<version>\d+\.\d+\.\d+)-build\.(?P<code>\d+)$')


def get_settings() -> Settings:
    return Settings.from_env()


def _github_headers(settings: Settings) -> dict[str, str]:
    return {
        'Accept': 'application/vnd.github+json',
        'Authorization': f'Bearer {settings.github_release_token}',
        'X-GitHub-Api-Version': '2026-03-10',
    }


def _public_release(release: dict, settings: Settings) -> dict | None:
    """Public update metadata for *release*, or None when it is not an update.

    Never returned to a client unchanged: the release object contains the
    repository URL and asset API URLs, and only the fields below are public.
    """
    tag = str(release.get('tag_name', '')).strip()
    names = {
        str(asset.get('name', ''))
        for asset in release.get('assets', [])
        if isinstance(asset, dict)
    }

    semver = _SEMVER.match(tag)
    build = _BUILD_TAG.match(tag)

    # Shape 2 first, because `v0.1.1-build.42` is not a semver tag.
    if build and settings.github_release_asset in names:
        return {
            'enabled': True,
            # Kept for the first version of this endpoint.
            'version': build.group('version'),
            'version_code': int(build.group('code')),
            'notes': str(release.get('body', '') or '')[:1000],
            # The documented shape.
            'latest_version': build.group('version'),
            'release_notes': str(release.get('body', '') or '')[:1000],
            'download_url': '/v1/app/update/download',
            'asset_name': settings.github_release_asset,
        }

    if semver:
        asset_name = _semantic_asset_name(names, settings)
        if asset_name is None:
            return None
        return {
            'enabled': True,
            'version': semver.group(1),
            'latest_version': semver.group(1),
            'minimum_version': settings.app_min_supported_version or None,
            'release_notes': str(release.get('body', '') or '')[:2000],
            'notes': str(release.get('body', '') or '')[:1000],
            'download_url': '/v1/app/update/download',
            'asset_name': asset_name,
            'sha256': _sha256_from_release(release),
            'published_at': release.get('published_at'),
        }

    return None


def _semantic_asset_name(names: set[str], settings: Settings) -> str | None:
    """The APK asset for a semantic release: the configured name, else any APK."""
    if settings.github_release_asset in names:
        return settings.github_release_asset
    for name in sorted(names):
        if name.lower().endswith('.apk'):
            return name
    return None


def _sha256_from_release(release: dict) -> str | None:
    """The APK digest, when the release publishes one.

    Read from a ``*.apk.sha256`` asset if present, else from the release body
    (``sha256=<hex>``), which is how the release workflow records it. The app
    verifies the download against this, so a corrupt or substituted file never
    reaches the installer.
    """
    for asset in release.get('assets', []):
        if not isinstance(asset, dict):
            continue
        name = str(asset.get('name', '')).lower()
        if name.endswith('.apk.sha256'):
            digest = str(asset.get('body', '') or '')
            match = re.search(r'\b([0-9a-fA-F]{64})\b', digest)
            if match:
                return match.group(1).lower()
    match = re.search(r'sha256[=:\s]+([0-9a-fA-F]{64})', str(release.get('body', '') or ''))
    return match.group(1).lower() if match else None


def _pick_asset(release: dict, settings: Settings) -> dict | None:
    """The APK asset to stream, preferring the configured name."""
    assets = [a for a in release.get('assets', []) if isinstance(a, dict)]
    for asset in assets:
        if str(asset.get('name', '')) == settings.github_release_asset:
            return asset
    for asset in assets:
        if str(asset.get('name', '')).lower().endswith('.apk'):
            return asset
    return None


async def _fetch_release(settings: Settings) -> dict | None:
    """The most recent release that this endpoint would serve.

    ``/releases`` rather than ``/releases/latest``: the repository also contains
    CI build releases, and "latest" would hand back whichever was published last
    — including one that is not an app update at all. The first release in the
    list (GitHub returns them newest first) that yields public metadata wins.
    """
    url = f'https://api.github.com/repos/{settings.github_release_repo}/releases?per_page=20'
    try:
        timeout = aiohttp.ClientTimeout(total=10)
        async with aiohttp.ClientSession(timeout=timeout) as client:
            async with client.get(url, headers=_github_headers(settings)) as response:
                if response.status != 200:
                    return None
                releases = await response.json()
    except (aiohttp.ClientError, TimeoutError):
        return None

    if not isinstance(releases, list):
        return None
    for release in releases:
        if not isinstance(release, dict):
            continue
        # Drafts are not published and pre-releases are opt-in: neither should
        # ever be offered to a user as an update.
        if release.get('draft') or release.get('prerelease'):
            continue
        if _public_release(release, settings) is not None:
            return release
    return None


@router.get('/v1/app/update')
async def app_update(settings: Settings = Depends(get_settings)):
    if not settings.github_release_token:
        return {'enabled': False}

    release = await _fetch_release(settings)
    if release is None:
        return {'enabled': False}
    public = _public_release(release, settings)
    return public if public is not None else {'enabled': False}


@router.get('/v1/app/update/download')
async def download_app_update(settings: Settings = Depends(get_settings)) -> StreamingResponse:
    if not settings.github_release_token:
        raise HTTPException(status_code=404, detail='App updates are not configured.')

    headers = _github_headers(settings)

    async def stream():
        timeout = aiohttp.ClientTimeout(total=300)
        async with aiohttp.ClientSession(timeout=timeout) as client:
            release = await _fetch_release(settings)
            if release is None:
                return

            asset = _pick_asset(release, settings)
            if not asset:
                return

            asset_url = f"https://api.github.com/repos/{settings.github_release_repo}/releases/assets/{int(asset['id'])}"
            asset_headers = dict(headers)
            asset_headers['Accept'] = 'application/octet-stream'
            async with client.get(asset_url, headers=asset_headers) as asset_response:
                if asset_response.status != 200:
                    return
                async for chunk in asset_response.content.iter_chunked(1024 * 1024):
                    yield chunk

    return StreamingResponse(
        stream(),
        media_type='application/vnd.android.package-archive',
        headers={'Content-Disposition': 'attachment; filename="Aladin-release.apk"'},
    )
