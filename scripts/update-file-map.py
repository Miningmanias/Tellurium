#!/usr/bin/env python3
"""Index authored project files, excluding generated builds, worlds and caches."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULES = {
    "semantic-core": "Immutable semantic language and independent reference evaluation",
    "compiler-jvm": "Prebound executable CPU programs",
    "compiler-vulkan": "Vulkan GLSL source generation",
    "material-codec": "Immutable final-state encodings and validation",
    "spatial-data": "Tile identities, bounded bytes and shared ownership",
        "chunk-engine": "Bounded coordinator, routing and commit models",
        "frontend-mc1211": "Typed captured Minecraft lowering boundary",
        "runtime-vulkan": "Persistent Vulkan lifecycle and opt-in native compute",
        "oracle-and-replay": "Corpus comparator, counted replay and result artifacts",
        "neoforge-1211": "Minecraft snapshot/runtime composition and bootstrap tests",
    "fabric-1211": "Deferred loader frame",
}
EXCLUDED = {".git", ".gradle", "build", "run", "runs", ".idea", "__pycache__"}


def authored_files():
    def walk(directory):
        for path in sorted(directory.iterdir()):
            if path.name in EXCLUDED:
                continue
            if path.is_dir():
                yield from walk(path)
            elif path.suffix not in {".log", ".pyc"} and not path.name.startswith("hs_err_pid"):
                yield path.relative_to(ROOT)
    return list(walk(ROOT))


def role(path):
    parts = path.parts
    if "gameTest" in parts:
        return "Opt-in game bootstrap test/resource"
    if "test" in parts:
        return "Executable CPU test"
    if "main" in parts and path.suffix == ".java":
        return "Implementation or explicit public contract"
    if "resources" in parts:
        return "Packaged metadata/resource"
    if path.suffix == ".md":
        return "Documentation"
    if path.suffix in {".gradle", ".properties"} or path.name.startswith("gradlew"):
        return "Build configuration/tooling"
    return "Tooling, data or provenance"


def main():
    paths = [p for p in authored_files() if p.as_posix() != "docs/FILE_MAP.md"]
    lines = [
        "# Project file map", "",
        "Generated from the files on disk by `python scripts/update-file-map.py`.", "",
        "This inventory excludes Git state, build output, dependency caches and development worlds. "
        "It does not treat a planned file as implemented. See [IMPLEMENTATION_MAP.md](IMPLEMENTATION_MAP.md) "
        "for future files and [STATUS.md](STATUS.md) for observed validation.", "",
        f"Indexed project files: **{len(paths)}**, plus this generated index.", "",
    ]
    groups = {name: [] for name in MODULES}
    groups["Project tooling, documentation and evidence"] = []
    for path in paths:
        key = path.parts[0] if path.parts[0] in MODULES else "Project tooling, documentation and evidence"
        groups[key].append(path)
    for name, entries in groups.items():
        lines.extend([f"## {name}", ""])
        if name in MODULES:
            lines.extend([MODULES[name] + ".", ""])
        lines.extend(["| File | Role |", "| --- | --- |"])
        for path in entries:
            text = path.as_posix()
            lines.append(f"| [{text}](../{text}) | {role(path)} |")
        lines.append("")
    destination = ROOT / "docs" / "FILE_MAP.md"
    destination.write_text("\n".join(lines), encoding="utf-8")
    print(f"Indexed {len(paths)} project files in {destination}")


if __name__ == "__main__":
    main()
