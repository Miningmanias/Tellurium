#!/usr/bin/env python3
"""Fetches what the Fabric test scripts need for one Minecraft version, into build/ (never into the mod).

  build/test-mods/fabric-<digits>/vanilla/   ScalableLux for that version (every test run includes it)
  build/test-mods/fabric-api-<version>/      Fabric API
  build/installed-fabric-<version>/          Fabric's server launcher jar

The mods come from Modrinth and are checked against the SHA-512 its API gives; the launcher comes from
meta.fabricmc.net, which publishes no hash.  Files already there are left alone.

Usage: python scripts/fetch-fabric-test-files.py <Minecraft version> [Fabric loader version]
"""
import hashlib
import json
import pathlib
import sys
import urllib.parse
import urllib.request

AGENT = {'User-Agent': 'Tellurium-test-setup'}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=120) as response:
        return response.read()


def modrinth(project, minecraft, directory):
    query = urllib.parse.urlencode({'game_versions': json.dumps([minecraft]), 'loaders': json.dumps(['fabric'])})
    versions = json.loads(get(f'https://api.modrinth.com/v2/project/{project}/version?{query}'))
    if not versions:
        sys.exit(f'{project}: no Fabric version for Minecraft {minecraft} on Modrinth')
    entry = next((f for f in versions[0]['files'] if f.get('primary')), versions[0]['files'][0])
    target = directory / entry['filename']
    if target.is_file():
        print(f'have {target}')
        return
    data = get(entry['url'])
    if hashlib.sha512(data).hexdigest() != entry['hashes']['sha512']:
        sys.exit(f'{project}: SHA-512 of {entry["filename"]} does not match Modrinth')
    directory.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print(f'fetched {target} ({versions[0]["version_number"]})')


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    minecraft = sys.argv[1]
    loader = sys.argv[2] if len(sys.argv) > 2 else '0.19.5'
    build = pathlib.Path(__file__).resolve().parent.parent / 'build'
    digits = minecraft.replace('.', '')
    modrinth('scalablelux', minecraft, build / 'test-mods' / f'fabric-{digits}' / 'vanilla')
    modrinth('fabric-api', minecraft, build / 'test-mods' / f'fabric-api-{minecraft}')
    server = build / f'installed-fabric-{minecraft}'
    launcher = server / 'fabric-server-launch.jar'
    if launcher.is_file():
        print(f'have {launcher}')
    else:
        installer = json.loads(get('https://meta.fabricmc.net/v2/versions/installer'))[0]['version']
        data = get(f'https://meta.fabricmc.net/v2/versions/loader/{minecraft}/{loader}/{installer}/server/jar')
        if data[:2] != b'PK':
            sys.exit('the server launcher that came back is not a jar')
        server.mkdir(parents=True, exist_ok=True)
        launcher.write_bytes(data)
        print(f'fetched {launcher} (loader {loader}, installer {installer})')


if __name__ == '__main__':
    main()
