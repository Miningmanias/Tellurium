#!/usr/bin/env python3
"""Create the Modrinth project page (as a draft) from docs/modrinth/.

Usage: MODRINTH_TOKEN=mrp_... python3 scripts/publish-modrinth-project.py [--update]

Without --update the project is created; with --update the existing project's
text and links are replaced and the icon is re-uploaded. Versions (jars) are
not uploaded here. The token needs the "Create projects" / "Write projects"
scopes and is read only from the environment.
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

API = "https://api.modrinth.com/v2"
ROOT = Path(__file__).resolve().parent.parent
PAGE = ROOT / "docs" / "modrinth"
ICON = ROOT / "docs" / "branding" / "tellurium-logo-256.png"
USER_AGENT = "Miningmanias/Tellurium (modrinth-page-script)"


def request(method, url, token, body=None, content_type=None):
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", token)
    req.add_header("User-Agent", USER_AGENT)
    if content_type:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req) as resp:
            text = resp.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as err:
        sys.exit(f"{method} {url} failed: {err.code} {err.read().decode()}")


def multipart(fields, files):
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n'
                     f"Content-Type: application/json\r\n\r\n{value}\r\n".encode())
    for name, (filename, data, ctype) in files.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; '
                     f'filename="{filename}"\r\nContent-Type: {ctype}\r\n\r\n'.encode() + data + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def main():
    token = os.environ.get("MODRINTH_TOKEN")
    if not token:
        sys.exit("Set MODRINTH_TOKEN")
    project = json.loads((PAGE / "project.json").read_text())
    project["body"] = (PAGE / "description.md").read_text()
    slug = project["slug"]

    if "--update" in sys.argv:
        patch = {k: project[k] for k in ("title", "description", "body", "categories",
                                         "additional_categories", "client_side", "server_side",
                                         "license_id", "source_url", "issues_url", "wiki_url")}
        request("PATCH", f"{API}/project/{slug}", token, json.dumps(patch).encode(), "application/json")
        request("PATCH", f"{API}/project/{slug}/icon?ext=png", token, ICON.read_bytes(), "image/png")
        print(f"Updated https://modrinth.com/mod/{slug}")
        return

    body, ctype = multipart({"data": json.dumps(project)},
                            {"icon": (ICON.name, ICON.read_bytes(), "image/png")})
    created = request("POST", f"{API}/project", token, body, ctype)
    print(f"Created draft https://modrinth.com/mod/{created['slug']} (id {created['id']})")


if __name__ == "__main__":
    main()
