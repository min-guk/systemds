# Production realization-support result

**Diagnostic only:** this host-JVM run was used to investigate implementation regressions. It is not an approved Docker performance comparison and is not used as evidence of end-to-end speed or memory improvement. The result report uses only the paired `run_LAN_docker.sh` measurements for those claims.

Recorded with one warmup and three measured freezes per engine on 2026-10-08. The benchmark class is compiled once against OLD and reused unchanged for NEW. The fixture is the existing flat-private aggregate model from `ExactPhysicalRealizationSupportFactorCacheTest`.

| Metric | OLD | NEW |
| --- | ---: | ---: |
| Variables | 55 | 55 |
| Hard factors | 159 | 159 |
| Logical hard-factor cells | 941,012 | 941,012 |
| Production finite-support factors | unavailable in OLD | 5 |
| Model build wall time | 153.477 ms | 162.407 ms |
| Model build allocated bytes | 42,488,904 | 42,704,952 |
| Median factor-freeze wall time | 943.393 ms | 626.424 ms |
| Median factor-freeze allocated bytes | 1,139,970,328 | 1,139,967,392 |
| Peak process RSS | 644,100 KiB | 624,256 KiB |

The sparse representation adds bounded work during model construction and avoids invalid-row evaluation for five production realization-support factors during freezing. In this run, freeze time decreased while total allocation stayed dominated by the fixture's other factors. This is a small synthetic fixture measurement, not an end-to-end FedPlanner runtime claim.
