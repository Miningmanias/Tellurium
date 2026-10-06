#!/usr/bin/env python3
"""Create the Modrinth project as a draft from docs/modrinth/.

Usage: MODRINTH_TOKEN=mrp_... python3 scripts/modrinth-create-project.py [--dry-run]

The token needs the CREATE_PROJECT scope. The project is created as a draft
with no versions; upload the jars and submit it for review on Modrinth.
Only the standard library is used.
"""
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = "https://api.modrinth.com/v2/project"
REPO = "https://github.com/Miningmanias/Tellurium"

DATA = {
    "slug": "tellurium",
    "title": "Tellurium",
    "description": "Faster chunk generation that produces the same world: "
                   "GPU terrain via Vulkan, parallel steps, background saving "
                   "and a built-in pregenerator.",
    "project_type": "mod",
    "client_side": "optional",
    "server_side": "required",
    "categories": ["optimization", "worldgen"],
    "additional_categories": ["utility"],
    "license_id": "MIT",
    "source_url": REPO,
    "issues_url": REPO + "/issues",
    "wiki_url": REPO + "/blob/main/docs/CONFIGURATION.md",
    "is_draft": True,
    "initial_versions": [],
}


def multipart(fields, files):
    boundary = uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n"
                "Content-Type: application/json\r\n\r\n").encode() + value.encode() + b"\r\n"
    for name, (filename, ctype, payload) in files.items():
        out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; "
                f"filename=\"{filename}\"\r\nContent-Type: {ctype}\r\n\r\n").encode() + payload + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


def main():
    data = dict(DATA, body=(ROOT / "docs/modrinth/description.md").read_text(encoding="utf-8"))
    icon = (ROOT / "docs/branding/tellurium-logo-256.png").read_bytes()
    if "--dry-run" in sys.argv:
        print(json.dumps({k: v for k, v in data.items() if k != "body"}, indent=2))
        print(f"body: {len(data['body'])} chars, icon: {len(icon)} bytes")
        return 0
    token = os.environ.get("MODRINTH_TOKEN")
    if not token:
        print("MODRINTH_TOKEN is not set", file=sys.stderr)
        return 2
    body, ctype = multipart({"data": json.dumps(data)},
                            {"icon": ("icon.png", "image/png", icon)})
    req = urllib.request.Request(API, data=body, method="POST", headers={
        "Authorization": token,
        "Content-Type": ctype,
        "User-Agent": "Miningmanias/Tellurium (modrinth-create-project.py)",
    })
    try:
        with urllib.request.urlopen(req) as resp:
            project = json.load(resp)
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode(errors='replace')}", file=sys.stderr)
        return 1
    print(f"Created draft https://modrinth.com/mod/{project['slug']} (id {project['id']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
