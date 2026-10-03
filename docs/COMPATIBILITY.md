# Compatibility policy

WorldgenNext keeps Minecraft's holder/ticket/status machinery authoritative. It owns only captured-input preparation, admitted CPU/GPU work, validation and the loader-injected commit callback.

Unknown density nodes, unsupported generators, populated targets and conflicting hooks fail closed with an explicit route/reason. Required matrix contexts cannot be relabeled CPU-only to satisfy the GPU gate.
