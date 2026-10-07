#!/usr/bin/env python3
"""Fetches Terralith and Tectonic for one Minecraft version and loader, laid out as the terralith, tectonic and
combined rows of scripts/verify-fast-matrix.sh expect them:

  <mods root>/terralith/   Terralith
  <mods root>/tectonic/    Tectonic
  <mods root>/combined/    both

each with the required dependencies Modrinth lists (other than Fabric API, which the development environment
has), Lithostitched with Tectonic in any case, and a copy of the jars in <mods root>/vanilla (ScalableLux: every test run includes it).  The first
version Modrinth returns for the Minecraft version and loader is taken; files are checked against the SHA-512
its API gives.  Prints what it fetched, which is what a result obtained with these files should cite.

Usage: python scripts/fetch-terrain-test-mods.py <Minecraft version> <fabric|neoforge> <mods root>
"""
import hashlib
import json
import pathlib
import shutil
import sys
import urllib.parse
import urllib.request

AGENT = {'User-Agent': 'Tellurium-test-setup'}
FABRIC_API = 'P7dR8mSH'


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=120) as response:
        return response.read()


def version_of(project, minecraft, loader):
    query = urllib.parse.urlencode({'game_versions': json.dumps([minecraft]), 'loaders': json.dumps([loader])})
    versions = json.loads(get(f'https://api.modrinth.com/v2/project/{project}/version?{query}'))
    if not versions:
        sys.exit(f'{project}: no {loader} version for Minecraft {minecraft} on Modrinth')
    return versions[0]


def fetch(project, minecraft, loader, cache, seen):
    """Downloads the project and its required dependencies into the cache; returns the jar paths."""
    if project in seen:
        return seen[project]
    version = version_of(project, minecraft, loader)
    entry = next((f for f in version['files'] if f.get('primary')), version['files'][0])
    target = cache / entry['filename']
    if not target.is_file():
        data = get(entry['url'])
        if hashlib.sha512(data).hexdigest() != entry['hashes']['sha512']:
            sys.exit(f'{project}: SHA-512 of {entry["filename"]} does not match Modrinth')
        target.write_bytes(data)
    print(f'{project}: {version["version_number"]} ({entry["filename"]})')
    jars = [target]
    seen[project] = jars
    for dependency in version['dependencies']:
        if dependency['dependency_type'] == 'required' and dependency.get('project_id') and dependency['project_id'] != FABRIC_API:
            jars += fetch(dependency['project_id'], minecraft, loader, cache, seen)
    return jars


def main():
    if len(sys.argv) != 4 or sys.argv[2] not in ('fabric', 'neoforge'):
        sys.exit(__doc__)
    minecraft, loader, root = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
    base = sorted((root / 'vanilla').glob('*.jar'))
    if not base:
        sys.exit(f'no jars in {root / "vanilla"}: fetch ScalableLux for this version first')
    cache = root / 'fetched'
    cache.mkdir(parents=True, exist_ok=True)
    seen = {}
    sets = {'terralith': fetch('terralith', minecraft, loader, cache, seen), 'tectonic': fetch('tectonic', minecraft, loader, cache, seen)}
    # Tectonic needs Lithostitched; its Modrinth entry does not say so for every version.
    sets['tectonic'] += [jar for jar in fetch('lithostitched', minecraft, loader, cache, seen) if jar not in sets['tectonic']]
    sets['combined'] = sets['terralith'] + sets['tectonic']
    for name, jars in sets.items():
        directory = root / name
        directory.mkdir(parents=True, exist_ok=True)
        for jar in base + jars:
            if not (directory / jar.name).is_file():
                shutil.copy2(jar, directory / jar.name)
        print(f'{directory}: {sorted(p.name for p in directory.glob("*.jar"))}')


if __name__ == '__main__':
    main()
