# LogReg under-10 candidate01 measurement

**Verdict: candidate correctness passed; the `<10.0 s` planning gate failed.** Candidate01 was run
exactly once with the official Docker JFR wrapper. No baseline, retry, or GLM workload was run.

## Timing and resources

| Metric | Reused reference | Candidate01 | Observed change |
| --- | ---: | ---: | ---: |
| Memo planning | 26.819446540 s | 16.103018585 s | -10.716427955 s (-39.9577%) |
| Facts | 0.057448992 s | 0.053722228 s | -6.49% |
| Rules | 2.100982921 s | 1.493638574 s | -28.90% |
| Model | 0.892350329 s | 0.902680564 s | +1.16% |
| Optimization | 23.714206977 s | 13.563777113 s | -10.150429864 s (-42.8032%) |
| Projection | 0.054457321 s | 0.089200106 s | +63.80% |
| Compilation | 28.121125 s | 17.307357 s | -38.45% |
| Runtime | 3.034 s | 2.690 s | -11.34% |
| Coordinator CPU | 51.61 s | 39.61 s | -23.25% |
| Peak RSS | 758,020 KiB | 574,300 KiB | -24.24% |
| Peak HWM | 785,668 KiB | 667,616 KiB | -15.03% |

Planning remains 6.103018585 s above the strict target. These are one-run JFR observations.

## Correctness gates

Every gate except planning time passed. Status is `OPTIMAL`; the exact objective, output CSV bytes,
FED fingerprint, selected action diagnostics, normalized runtime audit, Oracle trace (`hits=0`,
`misses=1057`), native universe and requests, three-worker count, 920 native rules, one supply, and
maximum numeric difference are unchanged. The candidate engine's 12,509-file manifest and all four
Docker frozen-input trees match exactly. JFR parsed with no data-loss event. The exact run container
and coordinator monitor exited and were removed.

Search nodes remain 2,933,891 and bound prunes remain 2,466,239. Therefore the stronger correlated
boundary bound did not prune an additional node in this workload. The fed log contains no separate
reduced-core graph/component-count diagnostic; only the standard Memo search counters are present.

## Movement reuse

The new trace reports:

- `activationRows=2960`, `activationReplays=148482`
- `postGateRequests=192331`, `postGateBuilds=2589`, `postGateHits=189742` (98.654% hit rate)
- `primitiveContexts=10709`, `contextualViewBuilds=35725`

The movement optimization materially removed resolver and contextual shape work. Inclusive JFR
samples fell from 711 to 84 for `movementGroupCost`, 543 to 28 for
`visitBindingCostContexts`, 758 to 6 for `visitJoint`, and 714 to 5 for resolver `enumerate`.
Binding-guard requests fell from 767,951 to 19,399. Total optimizer allocation weight fell from
30.120 GB to 10.104 GB (-66.46%).

## Residual bottleneck

Candidate01 has 1,110 optimizer execution samples. Absolute exclusive attribution is led by:

| Nearest Memo category | Samples | Candidate optimizer samples |
| --- | ---: | ---: |
| Search cache/key/domain | 369 | 33.24% |
| Reduced-search control | 222 | 20.00% |
| Solver other | 212 | 19.10% |
| Conditional cost bound | 143 | 12.88% |
| Shape/key/guard | 71 | 6.40% |

The largest leaf evidence is `ReducedSearch.enumerateCore` (155 samples), `BitSet.hashCode` under
`scopedDomainHash` (98), hash-tree lookup under `cachedDomains` (89), and `BitSet.equals` under
`ScopedDomainIdentity.sameDomains` (73). Inclusive `cachedDomains` rose from 229 to 333 samples,
`scopedDomainHash` from 56 to 140, while total optimizer samples fell. This exact-domain lookup is
now the clearest CPU target.

Conditional-bound queries remain essentially unchanged at 11,135,051 with 11,132,124 hits.
Although inclusive `computeConditionalCostBound` samples fell from 763 to 150 after movement reuse,
its nearest-category allocation weight is still 5.392 GB, 53.36% of candidate optimizer allocation.
That category includes bound lookup, `minimumTerm`, and nested exact cost work; it is not cache-key
allocation alone. Support and propagation also remain visible at 318 and 374 inclusive samples,
respectively, while support DFS is 53 samples.

The profile therefore supports a distinct candidate02 aimed at repeated exact-domain hash/equality
and lower-bound query overhead. It does not support another movement-context optimization as the
primary next step.

## Evidence

- JFR SHA-256: `906721e280ad4bf0073d528e6d290313311efd389e7f3f54498c84578bcf8a28`
- Machine-readable gate result: `candidate01-evaluation.json`
- Candidate summary and movement trace: `candidate01-summary.json`
- Focused JFR profile: `candidate01-hotspots.json`
- Engine/run-input verification: `candidate01-freeze-verification.json`
