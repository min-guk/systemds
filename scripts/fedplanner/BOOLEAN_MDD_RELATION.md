# Exact Boolean ROMDD relation core

`boolean_mdd_relation.py` represents a Boolean relation over a fixed ordered
list of finite categorical variables. It is intentionally producer-neutral and
is not wired into the P/E gate. A producer must first certify its variable
dictionary, scopes, and acceptance semantics.

The manager exposes dense factor compilation, canonical AND/OR/NOT,
existential elimination, directional difference, emptiness, exact counting,
and witness reconstruction. `to_artifact` emits reduced content-addressed
nodes. `validate_artifact` is a separate structural reader and evaluator; pass
an independently fixed `expected_variables` dictionary plus either separately
trusted `expected_roots` content commitments or exhaustive `replay_cases` to
detect a consistently re-signed semantic substitution. Copying roots from the
artifact itself only checks structure, not native acceptance or projection.
Partial replay is rejected because it cannot establish
relation equality outside the sampled assignments. Semantic certification
always requires `expected_variables`; graph root IDs alone do not bind category
labels or their order.

Set `max_nodes` and `max_apply_pairs` on `MDDManager` to impose fail-closed
construction limits. Exhausting either raises `MDDResourceLimitError` and never
returns a partial relation. `write_canonical_artifact` and artifact hashing use
incremental JSON encoding, avoiding a second full serialized-byte copy. The
in-memory node graph, artifact object, unique table, and caches still consume
space within the configured manager budgets.

For a diagram with `N` reachable nodes and maximum radix `R`, unary traversal
is `O(N*R)`. A cached binary apply is `O(N_left*N_right*R)` in the worst case,
usually bounded by the number of reached node pairs. Existential elimination
has the same apply-dependent worst case because it ORs children. Counting,
witness search, structural validation, and serialization are linear in the
reachable diagram size. Construction from a predicate is exhaustive in the
Cartesian product and is intended only as an oracle/test helper; real adapters
should compile factors and combine them.

Future P/E adapters can use one common certified variable dictionary, compile
each exact factor with `from_factor`, conjoin factors, existentially eliminate
proof-only coordinates, and compare both directional differences. Integration
must remain fail-closed until both producer scopes and acceptance predicates
are independently certified.
