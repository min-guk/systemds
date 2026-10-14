# Integrated-main LogReg publication measurement

The final integrated source receives exactly one official Docker JFR run of `ml_logreg` after the
root declares its build, regression suite, source freeze, and manifest ready. The original repository
entrypoint `scripts/fedplanner/run_LAN_docker.sh --joint-boundary-e2e` is used with the pinned image
`sha256:2816d74bddb56a977e16c54698b140907d8609132693e7793784a8a748b1b434`, three workers,
4 CPUs, 8 GiB, the 192x8 fixture, and `multiLogReg` parameters `maxi=10,maxii=5`. Only LogReg runs;
there is no retry or GLM run.

The integrated engine lives outside Git at
`/grid/3/cofee-lm-sweep-mchoi-20260914/logreg-main-publication-20261014/engine`. The run id is
`logreg-main-publication-01`. The run must match both the primary 26.819446540-second reference and
the final Candidate03 reference for exact objective, output CSV bytes, FED fingerprint, selected
actions, normalized runtime audit, Oracle trace, native universe and requests, worker count, native
rules, and supplies. It must return `OPTIMAL`, parse JFR without loss, match the frozen manifest and
Docker inputs, exercise a valid forest tail, and plan in strictly less than 10 seconds.

Integrated main includes shared privacy and return-shape fixes. Privacy, shape-reuse, movement-reuse,
forest-tail, search, and other request counters may therefore differ and are reported rather than
used as equality gates. Candidate03 planning, optimization, nodes, JFR profile, CPU, memory,
allocation, and GC are the incremental performance reference. All performance values are one-run
observations.
