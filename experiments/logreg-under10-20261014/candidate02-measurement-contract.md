# Candidate02 measurement contract

Candidate02 receives one official Docker JFR run of LogReg after the frozen engine is declared
ready. The primary reference remains `logreg-bottleneck-after-01`: planning 26.819446540 s and
optimization 23.714206977 s. Candidate01 is an incremental comparison only: planning
16.103018585 s and optimization 13.563777113 s.

The existing primary evaluator requires planning below 10 seconds, `OPTIMAL`, exact objective and
all output, FED, audit, Oracle, native-universe, worker and frozen-input gates. Candidate02 also has
an explicit differential gate requiring 2,933,891 search nodes, equal to candidate01. This prototype
changes compact domain representation, hashing, redundant bound arithmetic and exact connectivity,
while preserving its search heuristic. A changed node count therefore fails unless the implementation
owner first records a deliberate heuristic change.

The candidate01 JFR cannot recover scope widths or domain widths. Its stack samples retain method
names and line numbers but no arguments or local values. The 89 exclusive
`HashMap$TreeNode.getTreeNode` samples establish that tree-bin lookup occurred; they do not identify
bucket population, collisions, scope width or domain cardinality. Likewise, sampled `BitSet.hashCode`,
`BitSet.equals`, and `[J` allocation weights do not identify a particular bit set's logical length.
The aggregate Memo traces contain request counts rather than width histograms. Width-dependent claims
must therefore come from source invariants or future explicit counters, not this completed workload.
