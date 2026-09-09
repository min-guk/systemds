# Physical emission authority trace schema (2026-09-05)

## Problem

The original `Emission-RegistryWrite` detail flattened every REFED consumer into
one list and emitted only aggregate anchor/FType fields. That representation
could not recover which consumers belonged to each `AuthoritySpec`, and it did
not expose the planner action key applied by the REFED, FOUT, or LOCAL runtime
registry. `Emission-Candidate` similarly exposed only a SHA-256 digest for a
derived-FOUT materialization action. A physical-plan comparator could therefore
miss a change in runtime authority or could only return “needs more evidence”
when two derived-action hashes differed.

## Encoding

When planner tracing is enabled, the existing trace records and fields remain.
Two additional fields use unpadded Base64URL encoding of UTF-8 JSON, so spaces
or separators inside normalized signatures cannot alter the trace grammar:

* `Emission-Candidate.foutMaterializationAuthorityB64`
* `Emission-RegistryWrite.runtimeAuthorityB64`

The JSON object always starts with:

```json
{"schema":"cofee-physical-emission-authority/v1","kind":"..."}
```

JSON object members are written in the order documented below. Collections are
canonicalized before encoding. Consumers use
`{"consumerHopId":N,"inputPosition":N}` and retain exact input positions.
Numeric Hop IDs remain process-local and must be resolved by a comparator to
the canonical physical-Hop equivalence class represented by the occurrence
identities in the same complete emission trace. One physical Hop may represent
multiple logical occurrences after inlining.

### Candidate derived-FOUT action

For an absent action the field is `-`. Otherwise `kind` is
`DERIVED_FOUT_ACTION` and `normalizedSignature` is the complete
`DerivedFoutMaterializationActionKey.normalizedSignature()`, not a digest:

```json
{
  "schema":"cofee-physical-emission-authority/v1",
  "kind":"DERIVED_FOUT_ACTION",
  "normalizedSignature":"..."
}
```

### REFED registry authority

`kind` is `REFED`. `authorities` is the canonical sorted `AuthoritySpec` list;
each element retains its own consumers instead of using a flattened list:

```json
{
  "schema":"cofee-physical-emission-authority/v1",
  "kind":"REFED",
  "authorities":[{
    "anchorHopId":12,
    "anchorKey":"...",
    "materializationFType":"ROW",
    "consumerInputs":[{"consumerHopId":34,"inputPosition":0}],
    "requiresLocalMaterialization":false,
    "plannerActionKey":"..."
  }]
}
```

Nullable authority fields are encoded as JSON `null`; they are not replaced by
empty strings or aggregate defaults.

### FOUT registry authority

`kind` is `FOUT`. The payload contains `anchorHopId`,
`materializationFType`, `anchorLabel`, `anchorKey`, the exact grouped
`consumerInputs`, `exactConsumerAuthority=true`, and `plannerActionKey`.

### LOCAL registry authority

`kind` is `LOCAL`. The payload contains `materializationFType`, `reason`, the
exact grouped `consumerInputs`, and `plannerActionKey`.

## Safety and compatibility

Formatting executes only after `FederatedPlannerTrace.isEnabled()` has passed
inside `tracePreparedEmission`. It changes no planner selection, Hop mutation,
registry write, or runtime behavior. The historical hash and human-readable
fields remain present for diagnostics. A missing Base64 authority field never
proves physical equality, including for an explicitly identified historical
observer build. Definitive old/new comparison stages should use the same
observer backport and require the schema on both sides.

## Verification

`PhysicalEmissionTraceFormatterTest` decodes the wire payload and proves that:

* REFED consumer-to-authority grouping, action keys, and local-stage flags are
  retained and deterministic;
* regrouping the same flattened consumers changes the payload;
* FOUT and LOCAL planner action keys and applied fields are retained; and
* a candidate carries its complete derived-action signature while absence is
  represented by `-`.
