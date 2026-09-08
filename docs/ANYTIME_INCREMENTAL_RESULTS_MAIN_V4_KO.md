# AnytimeIncremental planning 비교

이 문서는 동일한 compact+exact Global과 AnytimeIncremental의 planning-only paired 결과다. 
총 128쌍 중 incremental JVM 완료는 120쌍, 목표 인증 성공은 112쌍이다. oracle과 model identity까지 paired 검증된 행은 120쌍이다. 목표 미달과 실패도 전체 분모에 남겼다.

| 지표 | Global | AnytimeIncremental | 분모 |
| --- | ---: | ---: | ---: |
| paired 검증 planner 중앙값 (s) | 1.153 | 1.447 | 120 |
| Incremental 성공 행의 launcher TTT 중앙값 (s) | 4.258 | 4.807 | 112 |

| Campaign | Workload | Profile | 목표 | 결과 | Target class | Planner G/I (s) | Launcher TTT G/I (s) | Objective G/I | 실제 regret | LB 검증 | Components initial→target/final | equalities | fullyRestored | RSS G/I (MiB) |
| --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |
| main-incremental-v4 | P1_FULL | lan | 3% | target-miss | — | 3.434/3.636 | 9.328/— | 27354855.849/37363143.210 | 36.587% | valid | 362→50 | 534 | False | 1510.3/1479.4 |
| main-incremental-v4 | P1_FULL | lan | 5% | target-miss | — | 4.796/3.666 | 10.713/— | 27354855.849/37363143.210 | 36.587% | valid | 362→50 | 534 | False | 1505.0/1517.6 |
| main-incremental-v4 | P1_FULL | wan_heavy | 3% | target-miss | — | 4.101/3.603 | 10.870/— | 75619554.228/94992344.623 | 25.619% | valid | 362→41 | 536 | False | 1504.9/1526.7 |
| main-incremental-v4 | P1_FULL | wan_heavy | 5% | target-miss | — | 4.579/3.903 | 10.048/— | 75619554.228/94992344.623 | 25.619% | valid | 362→41 | 536 | False | 1521.4/1504.9 |
| main-incremental-v4 | P1_FULL | wan_light | 3% | target-miss | — | 4.563/4.579 | 12.360/— | 29764608.733/40636658.332 | 36.527% | valid | 362→44 | 533 | False | 1530.0/1513.6 |
| main-incremental-v4 | P1_FULL | wan_light | 5% | target-miss | — | 3.350/3.751 | 8.602/— | 29764608.733/40636658.332 | 36.527% | valid | 362→44 | 533 | False | 1469.1/1478.0 |
| main-incremental-v4 | P1_FULL | wan_mid | 3% | target-miss | — | 3.624/3.844 | 10.549/— | 48566106.001/63267025.375 | 30.270% | valid | 362→41 | 536 | False | 1501.1/1480.5 |
| main-incremental-v4 | P1_FULL | wan_mid | 5% | target-miss | — | 3.565/3.917 | 8.676/— | 48566106.001/63267025.375 | 30.270% | valid | 362→41 | 536 | False | 1476.3/1483.4 |
| main-incremental-v4 | P2_PREP | lan | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 439.0/432.9 |
| main-incremental-v4 | P2_PREP | lan | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 423.8/461.1 |
| main-incremental-v4 | P2_PREP | wan_heavy | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 433.7/436.1 |
| main-incremental-v4 | P2_PREP | wan_heavy | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 443.8/429.6 |
| main-incremental-v4 | P2_PREP | wan_light | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 438.5/447.1 |
| main-incremental-v4 | P2_PREP | wan_light | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 425.6/428.9 |
| main-incremental-v4 | P2_PREP | wan_mid | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 427.5/434.8 |
| main-incremental-v4 | P2_PREP | wan_mid | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 446.2/437.1 |
| main-incremental-v4 | als | lan | 3% | target-partial | incremental-partial | 0.419/0.548 | 3.035/3.102 | 399082.210/399082.210 | 0.000% | valid | 34→7 | 45 | False | 558.4/602.0 |
| main-incremental-v4 | als | lan | 5% | target-partial | initial-bound-partial | 0.661/0.433 | 3.314/2.971 | 399082.210/400496.703 | 0.354% | valid | 34→34 | 0 | False | 583.1/575.1 |
| main-incremental-v4 | als | wan_heavy | 3% | target-partial | incremental-partial | 0.408/0.772 | 2.724/3.348 | 504434.455/504434.455 | 0.000% | valid | 34→7 | 40 | False | 557.4/604.7 |
| main-incremental-v4 | als | wan_heavy | 5% | target-partial | incremental-partial | 0.796/0.728 | 3.604/3.237 | 504434.455/504434.455 | 0.000% | valid | 34→7 | 40 | False | 561.8/593.6 |
| main-incremental-v4 | als | wan_light | 3% | target-partial | incremental-partial | 0.447/0.755 | 2.747/3.197 | 403620.118/403620.118 | 0.000% | valid | 34→7 | 40 | False | 558.0/604.7 |
| main-incremental-v4 | als | wan_light | 5% | target-partial | initial-bound-partial | 0.525/0.491 | 3.371/3.224 | 403620.118/406702.628 | 0.764% | valid | 34→34 | 0 | False | 548.4/566.1 |
| main-incremental-v4 | als | wan_mid | 3% | target-partial | incremental-partial | 0.641/0.536 | 2.851/3.041 | 447599.925/447599.925 | 0.000% | valid | 34→7 | 40 | False | 557.3/615.9 |
| main-incremental-v4 | als | wan_mid | 5% | target-partial | incremental-partial | 0.438/0.535 | 2.630/2.678 | 447599.925/447599.925 | 0.000% | valid | 34→17 | 22 | False | 581.2/594.8 |
| main-incremental-v4 | glm | lan | 3% | target-partial | initial-bound-partial | 2.944/2.284 | 12.013/12.301 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1615.6/1617.3 |
| main-incremental-v4 | glm | lan | 5% | target-partial | initial-bound-partial | 2.863/2.646 | 13.453/12.953 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1602.8/1592.0 |
| main-incremental-v4 | glm | wan_heavy | 3% | target-partial | incremental-partial | 2.907/3.877 | 12.382/13.792 | 39974.614/40421.635 | 1.118% | valid | 324→47 | 533 | False | 1603.9/1613.1 |
| main-incremental-v4 | glm | wan_heavy | 5% | target-partial | incremental-partial | 2.961/3.057 | 12.416/12.887 | 39974.614/40421.635 | 1.118% | valid | 324→98 | 368 | False | 1625.4/1621.5 |
| main-incremental-v4 | glm | wan_light | 3% | target-partial | incremental-partial | 2.971/3.354 | 12.867/12.451 | 10552.184/10578.797 | 0.252% | valid | 324→98 | 368 | False | 1614.6/1636.8 |
| main-incremental-v4 | glm | wan_light | 5% | target-partial | incremental-partial | 2.909/3.207 | 12.709/13.550 | 10552.184/10578.797 | 0.252% | valid | 324→98 | 368 | False | 1608.9/1629.7 |
| main-incremental-v4 | glm | wan_mid | 3% | target-partial | incremental-partial | 3.132/3.607 | 11.872/13.478 | 24744.297/24961.375 | 0.877% | valid | 324→93 | 411 | False | 1638.3/1633.6 |
| main-incremental-v4 | glm | wan_mid | 5% | target-partial | incremental-partial | 3.010/3.274 | 12.812/12.567 | 24744.297/24961.375 | 0.877% | valid | 324→98 | 368 | False | 1618.2/1636.3 |
| main-incremental-v4 | gmm-vvi | lan | 3% | target-partial | initial-bound-partial | 1.292/1.133 | 4.970/4.709 | 183918.775/184802.788 | 0.481% | valid | 339→339 | 0 | False | 1209.8/1096.3 |
| main-incremental-v4 | gmm-vvi | lan | 5% | target-partial | initial-bound-partial | 1.209/1.125 | 4.431/4.862 | 183918.775/184802.788 | 0.481% | valid | 339→339 | 0 | False | 1223.0/1105.6 |
| main-incremental-v4 | gmm-vvi | wan_heavy | 3% | target-partial | incremental-partial | 1.258/1.982 | 5.388/5.336 | 203702.988/205802.988 | 1.031% | valid | 339→45 | 505 | False | 1199.4/1213.7 |
| main-incremental-v4 | gmm-vvi | wan_heavy | 5% | target-partial | incremental-partial | 1.265/1.284 | 5.072/4.550 | 203702.988/207902.988 | 2.062% | valid | 339→83 | 379 | False | 1215.6/1221.9 |
| main-incremental-v4 | gmm-vvi | wan_light | 3% | target-partial | initial-bound-partial | 1.329/1.090 | 5.034/4.790 | 185687.337/186787.861 | 0.593% | valid | 339→339 | 0 | False | 1214.6/1097.5 |
| main-incremental-v4 | gmm-vvi | wan_light | 5% | target-partial | initial-bound-partial | 1.165/1.138 | 4.189/5.297 | 185687.337/186787.861 | 0.593% | valid | 339→339 | 0 | False | 1215.4/1096.1 |
| main-incremental-v4 | gmm-vvi | wan_mid | 3% | target-partial | incremental-partial | 1.307/1.301 | 5.410/4.570 | 194238.926/196338.926 | 1.081% | valid | 339→83 | 379 | False | 1201.1/1230.4 |
| main-incremental-v4 | gmm-vvi | wan_mid | 5% | target-partial | initial-bound-partial | 2.325/0.936 | 5.966/5.073 | 194238.926/198538.926 | 2.214% | valid | 339→339 | 0 | False | 1199.9/1093.9 |
| main-incremental-v4 | gnmf | lan | 3% | target-partial | incremental-partial | 0.607/0.707 | 2.945/2.565 | 4651.797/4651.797 | 0.000% | valid | 33→4 | 54 | False | 481.3/564.2 |
| main-incremental-v4 | gnmf | lan | 5% | target-partial | incremental-partial | 0.403/0.734 | 2.692/2.805 | 4651.797/4651.797 | 0.000% | valid | 33→4 | 54 | False | 487.4/566.9 |
| main-incremental-v4 | gnmf | wan_heavy | 3% | target-partial | incremental-partial | 0.608/0.706 | 2.607/2.705 | 15163.418/15163.418 | 0.000% | valid | 33→4 | 54 | False | 495.0/566.5 |
| main-incremental-v4 | gnmf | wan_heavy | 5% | target-partial | incremental-partial | 0.493/0.880 | 2.583/2.896 | 15163.418/15163.418 | 0.000% | valid | 33→4 | 54 | False | 488.9/575.1 |
| main-incremental-v4 | gnmf | wan_light | 3% | target-partial | incremental-partial | 0.627/0.837 | 2.747/2.856 | 5163.314/5163.314 | 0.000% | valid | 33→4 | 54 | False | 497.3/563.7 |
| main-incremental-v4 | gnmf | wan_light | 5% | target-partial | incremental-partial | 0.606/0.596 | 2.987/2.559 | 5163.314/5163.314 | 0.000% | valid | 33→4 | 54 | False | 491.3/556.5 |
| main-incremental-v4 | gnmf | wan_mid | 3% | target-partial | incremental-partial | 0.590/0.839 | 2.707/2.988 | 9515.238/9515.238 | 0.000% | valid | 33→4 | 54 | False | 495.0/556.8 |
| main-incremental-v4 | gnmf | wan_mid | 5% | target-partial | incremental-partial | 0.514/0.809 | 2.960/3.237 | 9515.238/9515.238 | 0.000% | valid | 33→4 | 54 | False | 482.2/568.3 |
| main-incremental-v4 | kmeans | lan | 3% | target-partial | incremental-partial | 0.846/1.080 | 3.403/3.866 | 29057.638/29335.932 | 0.958% | valid | 91→13 | 165 | False | 722.6/905.3 |
| main-incremental-v4 | kmeans | lan | 5% | target-partial | incremental-partial | 0.723/0.871 | 3.152/3.300 | 29057.638/29701.733 | 2.217% | valid | 91→20 | 125 | False | 724.1/833.5 |
| main-incremental-v4 | kmeans | wan_heavy | 3% | target-partial | incremental-partial | 0.723/0.968 | 3.379/3.293 | 60524.427/60524.427 | 0.000% | valid | 91→7 | 182 | False | 743.9/923.6 |
| main-incremental-v4 | kmeans | wan_heavy | 5% | target-partial | incremental-partial | 0.674/0.977 | 3.325/3.646 | 60524.427/61089.310 | 0.933% | valid | 91→7 | 180 | False | 722.6/884.6 |
| main-incremental-v4 | kmeans | wan_light | 3% | target-partial | incremental-partial | 0.723/0.991 | 3.146/3.568 | 32891.843/33181.968 | 0.882% | valid | 91→7 | 186 | False | 732.1/884.2 |
| main-incremental-v4 | kmeans | wan_light | 5% | target-partial | incremental-partial | 0.737/0.934 | 3.513/3.490 | 32891.843/33585.692 | 2.109% | valid | 91→11 | 167 | False | 724.8/861.0 |
| main-incremental-v4 | kmeans | wan_mid | 3% | target-partial | incremental-partial | 0.993/0.980 | 4.056/3.627 | 46364.464/46364.464 | 0.000% | valid | 91→7 | 182 | False | 724.1/901.6 |
| main-incremental-v4 | kmeans | wan_mid | 5% | target-partial | incremental-partial | 0.677/1.301 | 3.539/4.142 | 46364.464/46776.703 | 0.889% | valid | 91→7 | 180 | False | 720.2/903.7 |
| main-incremental-v4 | l2svm | lan | 3% | target-partial | incremental-partial | 0.922/1.054 | 3.705/3.927 | 1911.012/1921.012 | 0.523% | valid | 65→24 | 52 | False | 684.1/762.7 |
| main-incremental-v4 | l2svm | lan | 5% | target-partial | incremental-partial | 0.570/0.797 | 2.996/3.147 | 1911.012/1921.012 | 0.523% | valid | 65→24 | 52 | False | 686.1/787.4 |
| main-incremental-v4 | l2svm | wan_heavy | 3% | target-partial | incremental-partial | 0.639/0.800 | 3.425/3.805 | 164108.438/164108.438 | 0.000% | valid | 65→58 | 8 | False | 689.7/732.7 |
| main-incremental-v4 | l2svm | wan_heavy | 5% | target-partial | incremental-partial | 0.585/1.129 | 3.304/3.938 | 164108.438/166108.438 | 1.219% | valid | 65→62 | 4 | False | 682.7/705.6 |
| main-incremental-v4 | l2svm | wan_light | 3% | target-partial | incremental-partial | 0.692/1.146 | 3.477/3.703 | 9232.988/9232.988 | 0.000% | valid | 65→24 | 52 | False | 693.9/796.7 |
| main-incremental-v4 | l2svm | wan_light | 5% | target-partial | incremental-partial | 0.598/0.706 | 3.007/2.928 | 9232.988/9232.988 | 0.000% | valid | 65→51 | 15 | False | 684.9/735.0 |
| main-incremental-v4 | l2svm | wan_mid | 3% | target-partial | incremental-partial | 0.717/0.686 | 3.338/3.120 | 81927.800/81927.800 | 0.000% | valid | 65→58 | 8 | False | 714.2/724.7 |
| main-incremental-v4 | l2svm | wan_mid | 5% | target-partial | incremental-partial | 0.577/0.672 | 3.044/3.109 | 81927.800/82927.800 | 1.221% | valid | 65→62 | 4 | False | 678.1/714.9 |
| main-incremental-v4 | lm | lan | 3% | target-partial | incremental-partial | 1.058/1.074 | 3.279/3.716 | 1703.908/1703.908 | 0.000% | valid | 69→22 | 65 | False | 750.8/848.0 |
| main-incremental-v4 | lm | lan | 5% | target-partial | incremental-partial | 0.585/0.559 | 2.874/3.063 | 1703.908/1703.908 | 0.000% | valid | 69→65 | 4 | False | 732.0/598.5 |
| main-incremental-v4 | lm | wan_heavy | 3% | target-partial | incremental-partial | 0.680/0.625 | 3.286/2.918 | 20282.451/20282.451 | 0.000% | valid | 69→65 | 4 | False | 719.7/608.8 |
| main-incremental-v4 | lm | wan_heavy | 5% | target-partial | incremental-partial | 0.546/0.574 | 3.064/2.850 | 20282.451/20282.451 | 0.000% | valid | 69→65 | 4 | False | 746.5/601.5 |
| main-incremental-v4 | lm | wan_light | 3% | target-partial | incremental-partial | 0.618/0.816 | 2.897/3.211 | 2552.802/2552.802 | 0.000% | valid | 69→65 | 4 | False | 729.6/609.2 |
| main-incremental-v4 | lm | wan_light | 5% | target-partial | incremental-partial | 1.004/0.841 | 3.789/3.171 | 2552.802/2552.802 | 0.000% | valid | 69→65 | 4 | False | 710.3/615.7 |
| main-incremental-v4 | lm | wan_mid | 3% | target-partial | incremental-partial | 0.856/0.556 | 3.231/2.753 | 10951.458/10951.458 | 0.000% | valid | 69→65 | 4 | False | 737.4/603.4 |
| main-incremental-v4 | lm | wan_mid | 5% | target-partial | incremental-partial | 0.850/1.022 | 2.895/3.466 | 10951.458/10951.458 | 0.000% | valid | 69→65 | 4 | False | 730.6/596.8 |
| main-incremental-v4 | logreg | lan | 3% | target-partial | incremental-partial | 1.012/1.223 | 4.466/4.639 | 4407.343/4518.628 | 2.525% | valid | 133→45 | 126 | False | 1137.8/1218.2 |
| main-incremental-v4 | logreg | lan | 5% | target-partial | incremental-partial | 1.119/1.288 | 4.320/5.232 | 4407.343/4518.628 | 2.525% | valid | 133→45 | 126 | False | 1140.1/1207.5 |
| main-incremental-v4 | logreg | wan_heavy | 3% | target-partial | incremental-partial | 1.010/1.329 | 4.119/4.823 | 136798.929/137798.929 | 0.731% | valid | 133→28 | 170 | False | 1140.8/1215.2 |
| main-incremental-v4 | logreg | wan_heavy | 5% | target-partial | incremental-partial | 1.185/2.371 | 4.170/6.321 | 136798.929/137798.929 | 0.731% | valid | 133→28 | 170 | False | 1136.6/1219.0 |
| main-incremental-v4 | logreg | wan_light | 3% | target-partial | incremental-partial | 1.017/1.332 | 3.950/4.338 | 10536.663/10586.663 | 0.475% | valid | 133→28 | 170 | False | 1133.4/1230.8 |
| main-incremental-v4 | logreg | wan_light | 5% | target-partial | incremental-partial | 1.061/1.389 | 4.104/4.619 | 10536.663/10586.663 | 0.475% | valid | 133→28 | 170 | False | 1131.6/1229.8 |
| main-incremental-v4 | logreg | wan_mid | 3% | target-partial | incremental-partial | 1.328/1.583 | 5.609/5.209 | 70360.747/70860.747 | 0.711% | valid | 133→28 | 170 | False | 1150.7/1212.5 |
| main-incremental-v4 | logreg | wan_mid | 5% | target-partial | incremental-partial | 1.041/1.506 | 4.341/5.466 | 70360.747/70860.747 | 0.711% | valid | 133→28 | 170 | False | 1135.4/1214.9 |
| main-incremental-v4 | pca | lan | 3% | target-partial | initial-bound-partial | 0.508/0.606 | 2.487/2.687 | 18742.434/19008.302 | 1.419% | valid | 52→52 | 0 | False | 556.9/511.0 |
| main-incremental-v4 | pca | lan | 5% | target-partial | initial-bound-partial | 0.474/0.612 | 2.820/2.586 | 18742.434/19008.302 | 1.419% | valid | 52→52 | 0 | False | 535.6/529.1 |
| main-incremental-v4 | pca | wan_heavy | 3% | target-partial | incremental-partial | 0.496/0.547 | 2.450/2.857 | 23884.137/23884.137 | 0.000% | valid | 52→27 | 43 | False | 548.2/522.8 |
| main-incremental-v4 | pca | wan_heavy | 5% | target-partial | incremental-partial | 0.448/0.773 | 2.716/3.004 | 23884.137/23884.137 | 0.000% | valid | 52→44 | 14 | False | 515.1/524.6 |
| main-incremental-v4 | pca | wan_light | 3% | target-partial | initial-bound-partial | 0.594/0.765 | 3.011/2.848 | 19070.757/19374.409 | 1.592% | valid | 52→52 | 0 | False | 525.0/495.8 |
| main-incremental-v4 | pca | wan_light | 5% | target-partial | initial-bound-partial | 0.614/0.479 | 2.580/2.880 | 19070.757/19374.409 | 1.592% | valid | 52→52 | 0 | False | 512.6/515.4 |
| main-incremental-v4 | pca | wan_mid | 3% | target-partial | incremental-partial | 0.430/0.509 | 2.580/2.687 | 21278.541/21278.541 | 0.000% | valid | 52→44 | 14 | False | 523.3/516.8 |
| main-incremental-v4 | pca | wan_mid | 5% | target-partial | incremental-partial | 0.647/0.633 | 2.593/2.546 | 21278.541/21576.200 | 1.399% | valid | 52→49 | 5 | False | 516.2/512.2 |
| main-incremental-v4 | sliceline-adult | lan | 3% | target-partial | incremental-partial | 1.442/1.572 | 5.661/5.108 | 514773.381/514773.381 | 0.000% | valid | 108→25 | 127 | False | 1260.7/1251.9 |
| main-incremental-v4 | sliceline-adult | lan | 5% | target-partial | incremental-partial | 1.425/1.784 | 5.798/5.525 | 514773.381/514773.381 | 0.000% | valid | 108→25 | 127 | False | 1250.6/1267.6 |
| main-incremental-v4 | sliceline-adult | wan_heavy | 3% | target-partial | incremental-partial | 1.481/2.263 | 5.411/6.626 | 1333351.423/1333351.423 | 0.000% | valid | 108→25 | 127 | False | 1249.9/1256.1 |
| main-incremental-v4 | sliceline-adult | wan_heavy | 5% | target-partial | incremental-partial | 1.421/1.634 | 5.383/5.192 | 1333351.423/1333351.423 | 0.000% | valid | 108→25 | 127 | False | 1245.5/1273.8 |
| main-incremental-v4 | sliceline-adult | wan_light | 3% | target-partial | incremental-partial | 1.412/1.822 | 4.641/5.719 | 556465.597/556465.597 | 0.000% | valid | 108→25 | 127 | False | 1248.2/1253.3 |
| main-incremental-v4 | sliceline-adult | wan_light | 5% | target-partial | incremental-partial | 1.280/1.656 | 4.637/5.199 | 556465.597/556465.597 | 0.000% | valid | 108→25 | 127 | False | 1255.3/1251.0 |
| main-incremental-v4 | sliceline-adult | wan_mid | 3% | target-partial | incremental-partial | 1.415/2.723 | 4.978/6.258 | 874580.383/874580.383 | 0.000% | valid | 108→25 | 127 | False | 1262.9/1253.1 |
| main-incremental-v4 | sliceline-adult | wan_mid | 5% | target-partial | incremental-partial | 1.496/1.706 | 5.237/5.823 | 874580.383/874580.383 | 0.000% | valid | 108→25 | 127 | False | 1261.2/1249.9 |
| main-incremental-v4 | sliceline-covtype | lan | 3% | target-partial | incremental-partial | 1.374/1.561 | 5.315/5.095 | 676492.438/676492.438 | 0.000% | valid | 108→22 | 130 | False | 1256.2/1261.3 |
| main-incremental-v4 | sliceline-covtype | lan | 5% | target-partial | incremental-partial | 1.689/1.580 | 6.054/4.828 | 676492.438/676492.438 | 0.000% | valid | 108→22 | 130 | False | 1255.9/1273.8 |
| main-incremental-v4 | sliceline-covtype | wan_heavy | 3% | target-partial | incremental-partial | 1.359/1.680 | 4.750/5.001 | 1575667.124/1575667.124 | 0.000% | valid | 108→22 | 130 | False | 1252.1/1246.9 |
| main-incremental-v4 | sliceline-covtype | wan_heavy | 5% | target-partial | incremental-partial | 1.604/1.725 | 5.521/5.775 | 1575667.124/1575667.124 | 0.000% | valid | 108→22 | 130 | False | 1249.2/1255.6 |
| main-incremental-v4 | sliceline-covtype | wan_light | 3% | target-partial | incremental-partial | 2.582/1.687 | 6.771/6.016 | 722561.622/722561.622 | 0.000% | valid | 108→30 | 122 | False | 1252.0/1267.2 |
| main-incremental-v4 | sliceline-covtype | wan_light | 5% | target-partial | incremental-partial | 1.489/2.563 | 5.811/5.618 | 722561.622/722561.622 | 0.000% | valid | 108→30 | 122 | False | 1251.5/1269.8 |
| main-incremental-v4 | sliceline-covtype | wan_mid | 3% | target-partial | incremental-partial | 1.336/1.547 | 4.797/4.692 | 1072070.981/1072070.981 | 0.000% | valid | 108→24 | 128 | False | 1249.2/1276.2 |
| main-incremental-v4 | sliceline-covtype | wan_mid | 5% | target-partial | incremental-partial | 1.360/1.726 | 4.793/5.634 | 1072070.981/1072070.981 | 0.000% | valid | 108→24 | 128 | False | 1257.9/1256.5 |
| main-incremental-v4 | sliceline-kdd98 | lan | 3% | target-partial | incremental-partial | 1.387/1.582 | 5.058/5.572 | 1865362.756/1865362.756 | 0.000% | valid | 108→22 | 130 | False | 1260.8/1253.6 |
| main-incremental-v4 | sliceline-kdd98 | lan | 5% | target-partial | incremental-partial | 1.530/1.589 | 4.740/5.165 | 1865362.756/1865362.756 | 0.000% | valid | 108→22 | 130 | False | 1239.5/1252.5 |
| main-incremental-v4 | sliceline-kdd98 | wan_heavy | 3% | target-partial | incremental-partial | 1.520/1.778 | 5.809/5.897 | 2770027.797/2770027.797 | 0.000% | valid | 108→22 | 130 | False | 1264.1/1272.0 |
| main-incremental-v4 | sliceline-kdd98 | wan_heavy | 5% | target-partial | incremental-partial | 2.211/2.705 | 6.265/5.958 | 2770027.797/2770027.797 | 0.000% | valid | 108→22 | 130 | False | 1263.0/1273.5 |
| main-incremental-v4 | sliceline-kdd98 | wan_light | 3% | target-partial | incremental-partial | 1.480/1.525 | 5.825/5.013 | 1911392.678/1911392.678 | 0.000% | valid | 108→22 | 130 | False | 1249.3/1253.2 |
| main-incremental-v4 | sliceline-kdd98 | wan_light | 5% | target-partial | incremental-partial | 1.325/2.442 | 4.785/6.264 | 1911392.678/1911392.678 | 0.000% | valid | 108→22 | 130 | False | 1257.6/1260.3 |
| main-incremental-v4 | sliceline-kdd98 | wan_mid | 3% | target-partial | incremental-partial | 1.383/1.659 | 4.751/5.427 | 2262815.700/2262815.700 | 0.000% | valid | 108→30 | 122 | False | 1256.5/1254.9 |
| main-incremental-v4 | sliceline-kdd98 | wan_mid | 5% | target-partial | incremental-partial | 1.345/1.614 | 4.979/5.337 | 2262815.700/2262815.700 | 0.000% | valid | 108→30 | 122 | False | 1261.2/1252.3 |
| main-incremental-v4 | sliceline-uscensus | lan | 3% | target-partial | incremental-partial | 1.482/1.531 | 4.916/5.070 | 728244.559/728244.559 | 0.000% | valid | 108→22 | 130 | False | 1257.3/1265.2 |
| main-incremental-v4 | sliceline-uscensus | lan | 5% | target-partial | incremental-partial | 1.932/1.723 | 5.778/5.853 | 728244.559/728244.559 | 0.000% | valid | 108→22 | 130 | False | 1272.7/1238.1 |
| main-incremental-v4 | sliceline-uscensus | wan_heavy | 3% | target-partial | incremental-partial | 1.892/2.089 | 6.170/6.039 | 1707933.240/1707933.240 | 0.000% | valid | 108→28 | 124 | False | 1255.8/1257.1 |
| main-incremental-v4 | sliceline-uscensus | wan_heavy | 5% | target-partial | incremental-partial | 1.503/1.764 | 5.686/5.848 | 1707933.240/1707933.240 | 0.000% | valid | 108→28 | 124 | False | 1268.3/1262.2 |
| main-incremental-v4 | sliceline-uscensus | wan_light | 3% | target-partial | incremental-partial | 1.396/1.565 | 4.869/5.847 | 775913.525/775913.525 | 0.000% | valid | 108→22 | 130 | False | 1272.8/1263.3 |
| main-incremental-v4 | sliceline-uscensus | wan_light | 5% | target-partial | incremental-partial | 1.294/1.799 | 4.727/6.256 | 775913.525/775913.525 | 0.000% | valid | 108→22 | 130 | False | 1263.7/1258.7 |
| main-incremental-v4 | sliceline-uscensus | wan_mid | 3% | target-partial | incremental-partial | 1.638/1.661 | 6.431/4.978 | 1154986.995/1154986.995 | 0.000% | valid | 108→30 | 122 | False | 1250.9/1271.4 |
| main-incremental-v4 | sliceline-uscensus | wan_mid | 5% | target-partial | incremental-partial | 1.339/2.518 | 4.851/7.349 | 1154986.995/1154986.995 | 0.000% | valid | 108→30 | 122 | False | 1256.2/1270.7 |
| main-incremental-v4 | steplm | lan | 3% | target-partial | incremental-partial | 1.209/2.516 | 4.848/6.565 | 1062470168.428/1062470168.428 | 0.000% | valid | 161→18 | 302 | False | 1222.8/1224.3 |
| main-incremental-v4 | steplm | lan | 5% | target-partial | incremental-partial | 1.117/2.100 | 4.195/5.222 | 1062470168.428/1062470168.428 | 0.000% | valid | 161→18 | 302 | False | 1219.9/1227.7 |
| main-incremental-v4 | steplm | wan_heavy | 3% | target-partial | incremental-partial | 1.133/2.220 | 4.179/5.791 | 2990504964.035/2990504964.035 | 0.000% | valid | 161→15 | 310 | False | 1226.9/1217.8 |
| main-incremental-v4 | steplm | wan_heavy | 5% | target-partial | incremental-partial | 1.185/2.298 | 4.402/5.492 | 2990504964.035/2990504964.035 | 0.000% | valid | 161→15 | 310 | False | 1228.2/1218.6 |
| main-incremental-v4 | steplm | wan_light | 3% | target-partial | incremental-partial | 1.133/2.252 | 4.820/5.520 | 1172560896.655/1172560896.655 | 0.000% | valid | 161→18 | 302 | False | 1219.4/1227.5 |
| main-incremental-v4 | steplm | wan_light | 5% | target-partial | incremental-partial | 1.133/2.211 | 4.402/5.916 | 1172560896.655/1172560896.655 | 0.000% | valid | 161→18 | 302 | False | 1221.9/1225.9 |
| main-incremental-v4 | steplm | wan_mid | 3% | target-partial | incremental-partial | 1.142/2.361 | 4.121/5.653 | 1945193993.907/1945193993.907 | 0.000% | valid | 161→15 | 316 | False | 1221.1/1224.6 |
| main-incremental-v4 | steplm | wan_mid | 5% | target-partial | incremental-partial | 1.087/2.375 | 4.411/6.199 | 1945193993.907/1945193993.907 | 0.000% | valid | 161→15 | 316 | False | 1218.8/1226.8 |

## 해석 계약

`target-partial`은 첫 목표 checkpoint에서 `fullyRestored=0`임을 뜻한다. `initial-bound-partial`은 refinement action과 복원 equality가 모두 0인 초기 하한 성공이다. `incremental-partial`은 둘 다 0보다 큰 실제 점진 강화 성공이다. 기존 `partial_incremental_win` 필드는 이제 실제 `incremental-partial`이면서 검증된 paired Global보다 전체 planner 시간이 짧을 때만 참이다. 이전의 모든 partial 승리 의미는 새 `partial_planner_win`에 보존했다. `target-fully-restored`는 목표 도달 시 relaxation이 이미 완전히 복원된 경우이며 partial 성과로 세지 않는다.

`target_reached`는 method checkpoint의 목표 도달을 뜻한다. Oracle 또는 fingerprint가 없으면 그 행은 `pair_verified=false`이고 certificate·regret·승리 집계에는 포함하지 않는다.

Stage 시간(bound control, compact reduction, component preparation/solve, projection, ordered seed, Regional)은 JSON의 `stage_times_at_outcome`에 보존했다. `bound_control_seconds`(`mbe_bound_seconds` 호환 별칭)는 초기 bound와 incremental bound controller의 누적 시간이며 component preparation/solve는 그 안의 하위 시간이다. 따라서 이 세 값을 독립 stage처럼 더하지 않는다. `orderedSeedNanos`가 없는 구현은 0으로 바꾸지 않고 null로 기록한다.

이 결과는 encoded modeled-cost 문제의 인증이다. 실제 workload runtime을 인증하지 않는다.
