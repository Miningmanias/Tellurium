# Deferred Fabric adapter

This module reserves the future Fabric1.21.1 integration boundary. It intentionally has no entrypoint, loader metadata, Loom dependency or generation hook. Its `jar` and `sourcesJar` tasks are disabled: v0.1 does not produce a loadable Fabric artifact.

The future adapter will depend on the shared semantic/frontend interfaces, translate loader lifecycle events and registry identities, then invoke an independently qualified generation bridge. It must share compiler, scheduler and material semantics with the NeoForge adapter. Fabric packaging, platform-specific chunk-system integration and same-stack oracle campaigns are later milestones after the Minecraft frontend exists.
