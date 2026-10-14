# Memo migration policy authority

The current user explicitly authorized implementing the integrated Memo architecture from a fresh origin/main. The specification intentionally replaces legacy data-anchor search with a fixed W/canonical pool descriptor and allows independently lowered legal entry/exit/body transfers. These decisions supersede historical AGENTS.md requirements to discover a live data anchor; they do not waive privacy, actual runtime capability, source availability or TRead/TWrite native carrier semantics. Required runtime paths must be implemented and tested, not enabled through a compatibility bypass.

The user also adopted a validation matrix with PUBLIC and protected semantic fixtures. PUBLIC fixtures remain enabled because they are necessary to check exact legal-space/cost/emission parity; protecting only private paths cannot validate general movement correctness. This supersedes the historical instruction to ignore PUBLIC tests. No arbitrary opcode suppression, fake success, runtime fallback or unsupported CP/FOUT transient carrier is permitted.

Backedge movement remains forbidden; ordinary loop-body movement and branch join transfer remain legal. The design specifies complete native enumeration and exact global choice; a legacy execution path under a new class name is not the requested implementation.
