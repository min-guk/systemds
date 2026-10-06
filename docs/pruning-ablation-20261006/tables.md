# Pruning ablation

The primary aggregate covers the nine original non-STEP workloads. The compatible STEP-LM run is a separate stratum; it does not recover the failed original STEP-LM workload.

| Workload | Baseline | +Dominance | +Support | +Local | +Global |
|---|---:|---:|---:|---:|---:|
| als | 3.925 | 4.069 | 3.710 | 4.140 | 4.351 |
| glm | 75.816 | 73.479 | 77.884 | 78.981 | 82.026 |
| gmm | 6.227 | 6.441 | 6.037 | 6.605 | 6.093 |
| gnmf | 3.092 | 3.520 | 3.269 | 3.274 | 3.287 |
| kmeans | 6.086 | 5.845 | 5.742 | 6.076 | 5.665 |
| l2svm | 10.292 | 10.519 | 10.565 | 10.778 | 10.425 |
| lm | 2.208 | 2.018 | 2.231 | 2.340 | 2.272 |
| logreg | 28.930 | 28.132 | 28.027 | 28.870 | 29.602 |
| pca | 2.720 | 2.769 | 2.767 | 2.702 | 3.014 |
| steplm-compatible* | 9.120 | 8.746 | 8.679 | 9.402 | 9.381 |

Seconds; median of three successful repetitions.

## Global versus local

| Workload | Local upper | Global upper | Signed cost change | Local lower | Global lower | Local values | Global values | Extra final pruning | Stop / gap |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| als | 1348514.7 | 1348514.7 | +0.00% (unchanged) | 1302540.3 | 1302540.3 | 358 | 320 | 38 | TARGET_REACHED / 0.0353 |
| glm | 79553.5 | 79553.5 | +0.00% (unchanged) | 0 | 0 | 186147 | 186147 | 0 | RESOURCE_INITIAL / inf |
| gmm | 8225.2386 | 8225.2386 | +0.00% (unchanged) | 8225.2386 | 8225.2386 | 1107 | 766 | 341 | TARGET_REACHED / 5.308e-15 |
| gnmf | 10947.473 | 11206.622 | +2.37% (increase) | 10947.473 | 10884.508 | 575 | 343 | 232 | TARGET_REACHED / 0.02959 |
| kmeans | 2758.986 | 2758.986 | +0.00% (unchanged) | 2758.986 | 2758.986 | 1696 | 1671 | 25 | EXACT / 0 |
| l2svm | 1691.4353 | 1243.8568 | -26.46% (decrease) | 389.58927 | 455.25065 | 9168 | 2224 | 6944 | RESOURCE / 1.732 |
| lm | 51.407386 | 51.407386 | +0.00% (unchanged) | 50.168659 | 51.168659 | 208 | 122 | 86 | TARGET_REACHED / 0.004666 |
| logreg | 74333.656 | 74333.656 | +0.00% (unchanged) | 304.06322 | 304.06322 | 23762 | 23762 | 0 | RESOURCE / 243.5 |
| pca | 1998.5261 | 1998.5261 | +0.00% (unchanged) | 1998.3183 | 1971.472 | 385 | 352 | 33 | TARGET_REACHED / 0.01372 |
| steplm-compatible | 36712074 | 36712074 | +0.00% (unchanged) | 36699672 | 36699672 | 1071 | 1071 | 0 | TARGET_REACHED / 0.0003379 |

`globalPruned` counts value rejections on original domains, including values already absent after existing reduction; subsequent propagation can further shrink domains. The extra-pruning column therefore uses final `reducedValues` relative to local as the net effect.

The same gap policy can accept different selected costs after the global bound changes the search path or reduces resource work. A positive signed cost change is an increase, not a gain.

## Geometric-mean ratios

| Stratum | Variant | Paired planning / baseline | Ratio-of-medians planning / baseline | Paired optimizer / baseline | Paired cost / baseline | Paired global planning / local | Paired global cost / local |
|---|---|---:|---:|---:|---:|---:|---:|
| primary_original_nonstep_9 | baseline | 1.000 | 1.000 | 1.000 | 1.000 | nan | nan |
| primary_original_nonstep_9 | dominance | 0.998 | 1.006 | 1.037 | 1.000 | nan | nan |
| primary_original_nonstep_9 | support | 0.991 | 0.995 | 1.027 | 1.000 | nan | nan |
| primary_original_nonstep_9 | local | 1.000 | 1.034 | 0.955 | 1.000 | nan | nan |
| primary_original_nonstep_9 | global | 1.014 | 1.036 | 1.213 | 0.969 | 1.014 | 0.969 |
| optional_with_steplm_compatible_10 | baseline | 1.000 | 1.000 | 1.000 | 1.000 | nan | nan |
| optional_with_steplm_compatible_10 | dominance | 0.995 | 1.001 | 1.035 | 1.000 | nan | nan |
| optional_with_steplm_compatible_10 | support | 0.986 | 0.991 | 1.019 | 1.000 | nan | nan |
| optional_with_steplm_compatible_10 | local | 1.003 | 1.034 | 0.969 | 1.000 | nan | nan |
| optional_with_steplm_compatible_10 | global | 1.018 | 1.035 | 1.192 | 0.972 | 1.015 | 0.972 |

Primary ratios pair the same cold-JVM repetition before taking a geometric mean (27 pairs for the nine-workload stratum). Ratios of the three-repetition medians are descriptive only. Ratios below 1 are lower/faster; n=3 is exploratory and no statistical significance is claimed. The ten-workload aggregate includes the separately labelled compatible STEP-LM workload.
