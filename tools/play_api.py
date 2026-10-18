#!/usr/bin/env python3
"""Minimal Google Play Developer API (androidpublisher v3) client for releasing Lodestar.

Credentials: a service-account JSON key whose path is in PLAY_SERVICE_ACCOUNT (never commit the key). The account
must be invited in Play Console (Users and permissions) with release and store-presence permissions for the app.

    import play_api as play
    edit = play.insert_edit()
    ...
    play.commit_edit(edit)

Requires PyJWT with the cryptography backend.
"""

from __future__ import annotations

import json
import mimetypes
import os
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

PACKAGE = "net.kuecker.lodestar"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
_token = {"value": None, "expires": 0}


def token() -> str:
    """An OAuth access token from the service account (JWT bearer grant), cached until shortly before expiry."""
    now = int(time.time())
    if _token["value"] and now < _token["expires"] - 120:
        return _token["value"]
    with open(os.environ["PLAY_SERVICE_ACCOUNT"]) as handle:
        account = json.load(handle)
    assertion = jwt.encode(
        {"iss": account["client_email"], "scope": SCOPE, "aud": account["token_uri"], "iat": now, "exp": now + 3600},
        account["private_key"], algorithm="RS256", headers={"kid": account["private_key_id"]},
    )
    body = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}).encode()
    with urllib.request.urlopen(urllib.request.Request(account["token_uri"], data=body), timeout=60) as response:
        result = json.load(response)
    _token["value"] = result["access_token"]
    _token["expires"] = now + int(result.get("expires_in", 3600))
    return _token["value"]


def call(method: str, url: str, body=None, data: bytes | None = None, content_type: str | None = None):
    """One request; returns (status, parsed JSON or None). `url` is relative to the app's API root unless absolute."""
    if not url.startswith("https://"):
        url = API + url
    payload = data if data is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(url, data=payload, method=method)
    request.add_header("Authorization", "Bearer " + token())
    if content_type or body is not None:
        request.add_header("Content-Type", content_type or "application/json")
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            text = response.read()
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        text = error.read()
        try:
            return error.code, json.loads(text)
        except json.JSONDecodeError:
            return error.code, {"raw": text[:500].decode(errors="replace")}


def check(result, what: str):
    status, body = result
    if status >= 300:
        raise RuntimeError(f"{what}: HTTP {status}: {json.dumps(body)[:800]}")
    return body


def insert_edit() -> str:
    return check(call("POST", "/edits", {}), "insert edit")["id"]


def commit_edit(edit: str, changes_not_sent_for_review: bool = False):
    query = "?changesNotSentForReview=true" if changes_not_sent_for_review else ""
    return check(call("POST", f"/edits/{edit}:commit{query}"), "commit edit")


def upload_file(path_in_api: str, file_path: str, content_type: str | None = None):
    """Media upload (bundles, images) to an edit sub-resource, e.g. f"/edits/{edit}/bundles"."""
    with open(file_path, "rb") as handle:
        data = handle.read()
    kind = content_type or mimetypes.guess_type(file_path)[0] or "application/octet-stream"
    return check(call("POST", UPLOAD + path_in_api + "?uploadType=media", data=data, content_type=kind),
                 f"upload {os.path.basename(file_path)}")
