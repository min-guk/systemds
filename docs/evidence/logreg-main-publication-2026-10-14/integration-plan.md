# Main publication integration

Target: git@github.com:min-guk/systemds.git refs/heads/main, originallyf9faf7b97ae1a3dcc39a6178258b373f4fd272d2. The development clone origin points to the local original workspace; no original-workspace worktree files or unpublished GLM changes are modified.

Integrate functional commits2aff96c87f,2cf36e8d80,25662ee7b4,fd3c506000 and evidence commitc3d6465856 on the current remote main. Do not merge the independent full-migration snapshotabb5a4822a or frozen build artifacts. Preserve main privacy object reuse, residency fixes, complete return/guard shape cache identity, call indexes and all main-only tests. Retain both session histories.

After integration: offline Maven test-compile, the50 validated candidate classes plus5 main-only analysis/privacy/return-shape classes, Python harness, source checks and independent conflict review. Validate the distinct merged implementation with exactly one official Docker LogReg JFR workload, same inputs/resources/options, exact objective/CSV/runtime audit gates and totalplanning<10s. Archive frozen source/class hashes. Publish only by a normal fast-forward push after checking current remote main again; if main advances, integrate and verify its changes without force.
