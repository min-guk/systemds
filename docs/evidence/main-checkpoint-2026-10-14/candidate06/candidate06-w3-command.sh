#!/usr/bin/env bash
set -euo pipefail
cd /home/mchoi/fedplanner-under3-20261014
exec scripts/fedplanner/run_LAN_docker.sh --memo-planning-matrix \
  --engine /home/mchoi/fedplanner-under3-20261014/experiments/actual-workload-support-20261014/candidate06-engine \
  --output-root /home/mchoi/actual-support-candidate06-w3-20261014 \
  --sealed-stage /home/mchoi/w1357-stage-main276-fixedpool-20261008T1345Z \
  --workers 3 \
  --workloads ml:logreg,ml:glm,ml:pca,ml:als,ml:kmeans,ml:lm,ml:l2svm,ml:steplm,ml:gnmf,ml:gmm,p1:P1_FULL,p2:P2_PREP,sliceline:ADULT,sliceline:COVTYPE
