# AnytimeIncremental planning 비교

이 문서는 동일한 compact+exact Global과 AnytimeIncremental의 planning-only paired 결과다. 
총 128쌍 중 incremental JVM 완료는 120쌍, 목표 인증 성공은 120쌍이다. oracle과 model identity까지 paired 검증된 행은 120쌍이다. 목표 미달과 실패도 전체 분모에 남겼다.

| 지표 | Global | AnytimeIncremental | 분모 |
| --- | ---: | ---: | ---: |
| paired 검증 planner 중앙값 (s) | 1.255 | 1.446 | 120 |
| Incremental 성공 행의 launcher TTT 중앙값 (s) | 4.516 | 4.835 | 120 |

| Campaign | Workload | Profile | 목표 | 결과 | Target class | Planner G/I (s) | Launcher TTT G/I (s) | Objective G/I | 실제 regret | LB 검증 | Components initial→target/final | equalities | fullyRestored | RSS G/I (MiB) |
| --- | --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- | ---: |
| main-incremental-v5 | P1_FULL | lan | 3% | target-partial | incremental-partial | 3.498/4.166 | 9.142/10.121 | 27354855.849/27354855.849 | 0.000% | valid | 362→26 | 678 | False | 1498.1/1513.1 |
| main-incremental-v5 | P1_FULL | lan | 5% | target-partial | incremental-partial | 3.390/4.251 | 9.951/10.079 | 27354855.849/27354855.849 | 0.000% | valid | 362→26 | 678 | False | 1516.3/1511.6 |
| main-incremental-v5 | P1_FULL | wan_heavy | 3% | target-partial | incremental-partial | 4.621/4.087 | 10.158/9.871 | 75619554.228/75619554.228 | 0.000% | valid | 362→26 | 665 | False | 1507.6/1482.3 |
| main-incremental-v5 | P1_FULL | wan_heavy | 5% | target-partial | incremental-partial | 3.463/4.191 | 10.139/9.641 | 75619554.228/75619554.228 | 0.000% | valid | 362→26 | 665 | False | 1513.0/1517.4 |
| main-incremental-v5 | P1_FULL | wan_light | 3% | target-partial | incremental-partial | 4.444/4.213 | 12.379/10.220 | 29764608.733/29764608.733 | 0.000% | valid | 362→26 | 678 | False | 1517.2/1516.2 |
| main-incremental-v5 | P1_FULL | wan_light | 5% | target-partial | incremental-partial | 3.365/4.248 | 9.884/9.723 | 29764608.733/29764608.733 | 0.000% | valid | 362→26 | 678 | False | 1515.6/1486.7 |
| main-incremental-v5 | P1_FULL | wan_mid | 3% | target-partial | incremental-partial | 3.563/4.311 | 9.506/9.835 | 48566106.001/48566106.001 | 0.000% | valid | 362→26 | 665 | False | 1507.4/1477.6 |
| main-incremental-v5 | P1_FULL | wan_mid | 5% | target-partial | incremental-partial | 3.721/6.075 | 9.955/12.842 | 48566106.001/48566106.001 | 0.000% | valid | 362→26 | 665 | False | 1495.9/1507.9 |
| main-incremental-v5 | P2_PREP | lan | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 425.0/435.2 |
| main-incremental-v5 | P2_PREP | lan | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 445.6/431.9 |
| main-incremental-v5 | P2_PREP | wan_heavy | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 443.8/441.0 |
| main-incremental-v5 | P2_PREP | wan_heavy | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 437.0/431.8 |
| main-incremental-v5 | P2_PREP | wan_light | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 436.2/436.2 |
| main-incremental-v5 | P2_PREP | wan_light | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 437.9/442.4 |
| main-incremental-v5 | P2_PREP | wan_mid | 3% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 433.5/437.9 |
| main-incremental-v5 | P2_PREP | wan_mid | 5% | runtime-failed | — | —/— | —/— | —/— | —% | invalid/unverified | —→— | — | — | 436.4/432.4 |
| main-incremental-v5 | als | lan | 3% | target-partial | incremental-partial | 0.681/0.556 | 3.265/3.173 | 399082.210/399082.210 | 0.000% | valid | 34→7 | 45 | False | 567.2/602.9 |
| main-incremental-v5 | als | lan | 5% | target-partial | initial-bound-partial | 0.624/0.519 | 3.340/3.317 | 399082.210/400496.703 | 0.354% | valid | 34→34 | 0 | False | 564.1/581.5 |
| main-incremental-v5 | als | wan_heavy | 3% | target-partial | incremental-partial | 0.439/0.561 | 2.721/2.969 | 504434.455/504434.455 | 0.000% | valid | 34→7 | 40 | False | 563.1/595.5 |
| main-incremental-v5 | als | wan_heavy | 5% | target-partial | incremental-partial | 0.745/0.693 | 3.487/3.140 | 504434.455/504434.455 | 0.000% | valid | 34→7 | 40 | False | 560.5/609.8 |
| main-incremental-v5 | als | wan_light | 3% | target-partial | incremental-partial | 0.595/0.546 | 2.986/2.907 | 403620.118/403620.118 | 0.000% | valid | 34→7 | 40 | False | 550.7/614.9 |
| main-incremental-v5 | als | wan_light | 5% | target-partial | initial-bound-partial | 0.820/0.466 | 3.146/2.838 | 403620.118/406702.628 | 0.764% | valid | 34→34 | 0 | False | 555.3/584.5 |
| main-incremental-v5 | als | wan_mid | 3% | target-partial | incremental-partial | 0.481/0.555 | 2.812/2.852 | 447599.925/447599.925 | 0.000% | valid | 34→7 | 40 | False | 548.7/602.7 |
| main-incremental-v5 | als | wan_mid | 5% | target-partial | incremental-partial | 0.632/0.901 | 2.990/3.785 | 447599.925/447599.925 | 0.000% | valid | 34→17 | 22 | False | 557.7/596.7 |
| main-incremental-v5 | glm | lan | 3% | target-partial | initial-bound-partial | 3.041/2.587 | 12.546/12.436 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1630.7/1621.1 |
| main-incremental-v5 | glm | lan | 5% | target-partial | initial-bound-partial | 2.961/2.593 | 12.417/12.433 | 8947.078/9138.829 | 2.143% | valid | 324→324 | 0 | False | 1619.4/1602.8 |
| main-incremental-v5 | glm | wan_heavy | 3% | target-partial | incremental-partial | 3.606/3.762 | 13.940/13.390 | 39974.614/40421.635 | 1.118% | valid | 324→47 | 533 | False | 1625.5/1628.1 |
| main-incremental-v5 | glm | wan_heavy | 5% | target-partial | incremental-partial | 2.815/3.074 | 12.700/12.795 | 39974.614/40421.635 | 1.118% | valid | 324→98 | 368 | False | 1613.0/1629.3 |
| main-incremental-v5 | glm | wan_light | 3% | target-partial | incremental-partial | 3.055/3.116 | 13.828/12.384 | 10552.184/10578.797 | 0.252% | valid | 324→98 | 368 | False | 1619.3/1640.4 |
| main-incremental-v5 | glm | wan_light | 5% | target-partial | incremental-partial | 3.046/4.060 | 15.138/13.972 | 10552.184/10578.797 | 0.252% | valid | 324→98 | 368 | False | 1618.0/1625.7 |
| main-incremental-v5 | glm | wan_mid | 3% | target-partial | incremental-partial | 3.881/3.106 | 11.984/12.654 | 24744.297/24961.375 | 0.877% | valid | 324→93 | 411 | False | 1626.6/1628.3 |
| main-incremental-v5 | glm | wan_mid | 5% | target-partial | incremental-partial | 3.914/3.058 | 16.546/13.638 | 24744.297/24961.375 | 0.877% | valid | 324→98 | 368 | False | 1617.3/1616.1 |
| main-incremental-v5 | gmm-vvi | lan | 3% | target-partial | initial-bound-partial | 1.345/0.909 | 4.526/4.799 | 183918.775/184802.788 | 0.481% | valid | 339→339 | 0 | False | 1196.9/1112.6 |
| main-incremental-v5 | gmm-vvi | lan | 5% | target-partial | initial-bound-partial | 2.072/0.977 | 5.574/4.658 | 183918.775/184802.788 | 0.481% | valid | 339→339 | 0 | False | 1186.3/1106.3 |
| main-incremental-v5 | gmm-vvi | wan_heavy | 3% | target-partial | incremental-partial | 1.257/2.174 | 4.797/5.297 | 203702.988/205802.988 | 1.031% | valid | 339→45 | 505 | False | 1206.1/1211.8 |
| main-incremental-v5 | gmm-vvi | wan_heavy | 5% | target-partial | incremental-partial | 1.150/2.305 | 4.507/6.039 | 203702.988/207902.988 | 2.062% | valid | 339→83 | 379 | False | 1224.3/1214.1 |
| main-incremental-v5 | gmm-vvi | wan_light | 3% | target-partial | initial-bound-partial | 1.292/1.236 | 4.336/4.981 | 185687.337/186787.861 | 0.593% | valid | 339→339 | 0 | False | 1219.5/1072.9 |
| main-incremental-v5 | gmm-vvi | wan_light | 5% | target-partial | initial-bound-partial | 1.238/1.065 | 4.233/4.321 | 185687.337/186787.861 | 0.593% | valid | 339→339 | 0 | False | 1239.5/1095.6 |
| main-incremental-v5 | gmm-vvi | wan_mid | 3% | target-partial | incremental-partial | 1.390/1.292 | 5.272/4.596 | 194238.926/196338.926 | 1.081% | valid | 339→83 | 379 | False | 1212.8/1221.2 |
| main-incremental-v5 | gmm-vvi | wan_mid | 5% | target-partial | initial-bound-partial | 1.223/1.030 | 4.794/4.485 | 194238.926/198538.926 | 2.214% | valid | 339→339 | 0 | False | 1220.6/1099.9 |
| main-incremental-v5 | gnmf | lan | 3% | target-partial | incremental-partial | 0.371/0.815 | 2.639/3.236 | 4651.797/4651.797 | 0.000% | valid | 33→4 | 54 | False | 483.8/545.3 |
| main-incremental-v5 | gnmf | lan | 5% | target-partial | incremental-partial | 0.614/0.518 | 2.991/2.780 | 4651.797/4651.797 | 0.000% | valid | 33→4 | 54 | False | 509.5/571.1 |
| main-incremental-v5 | gnmf | wan_heavy | 3% | target-partial | incremental-partial | 0.500/0.853 | 2.452/3.175 | 15163.418/15163.418 | 0.000% | valid | 33→4 | 54 | False | 492.8/552.4 |
| main-incremental-v5 | gnmf | wan_heavy | 5% | target-partial | incremental-partial | 0.625/0.509 | 2.983/2.733 | 15163.418/15163.418 | 0.000% | valid | 33→4 | 54 | False | 487.9/575.4 |
| main-incremental-v5 | gnmf | wan_light | 3% | target-partial | incremental-partial | 0.591/0.528 | 2.873/2.861 | 5163.314/5163.314 | 0.000% | valid | 33→4 | 54 | False | 492.5/565.2 |
| main-incremental-v5 | gnmf | wan_light | 5% | target-partial | incremental-partial | 0.441/0.843 | 2.366/3.225 | 5163.314/5163.314 | 0.000% | valid | 33→4 | 54 | False | 496.0/536.6 |
| main-incremental-v5 | gnmf | wan_mid | 3% | target-partial | incremental-partial | 0.373/0.812 | 2.468/3.118 | 9515.238/9515.238 | 0.000% | valid | 33→4 | 54 | False | 483.7/558.5 |
| main-incremental-v5 | gnmf | wan_mid | 5% | target-partial | incremental-partial | 0.545/0.716 | 2.852/3.023 | 9515.238/9515.238 | 0.000% | valid | 33→4 | 54 | False | 502.9/555.3 |
| main-incremental-v5 | kmeans | lan | 3% | target-partial | incremental-partial | 0.667/1.319 | 3.081/3.872 | 29057.638/29335.932 | 0.958% | valid | 91→13 | 165 | False | 714.3/911.3 |
| main-incremental-v5 | kmeans | lan | 5% | target-partial | incremental-partial | 0.891/1.412 | 4.075/3.786 | 29057.638/29701.733 | 2.217% | valid | 91→20 | 125 | False | 718.4/817.8 |
| main-incremental-v5 | kmeans | wan_heavy | 3% | target-partial | incremental-partial | 0.675/0.958 | 3.136/3.352 | 60524.427/60524.427 | 0.000% | valid | 91→7 | 182 | False | 717.4/913.4 |
| main-incremental-v5 | kmeans | wan_heavy | 5% | target-partial | incremental-partial | 0.881/1.042 | 3.606/4.150 | 60524.427/61089.310 | 0.933% | valid | 91→7 | 180 | False | 702.5/872.0 |
| main-incremental-v5 | kmeans | wan_light | 3% | target-partial | incremental-partial | 0.746/1.285 | 3.459/3.822 | 32891.843/33181.968 | 0.882% | valid | 91→7 | 186 | False | 711.2/911.1 |
| main-incremental-v5 | kmeans | wan_light | 5% | target-partial | incremental-partial | 0.949/1.286 | 3.111/4.420 | 32891.843/33585.692 | 2.109% | valid | 91→11 | 167 | False | 710.1/871.2 |
| main-incremental-v5 | kmeans | wan_mid | 3% | target-partial | incremental-partial | 0.682/1.828 | 3.140/4.308 | 46364.464/46364.464 | 0.000% | valid | 91→7 | 182 | False | 731.4/898.9 |
| main-incremental-v5 | kmeans | wan_mid | 5% | target-partial | incremental-partial | 0.991/0.975 | 3.444/3.325 | 46364.464/46776.703 | 0.889% | valid | 91→7 | 180 | False | 709.7/888.2 |
| main-incremental-v5 | l2svm | lan | 3% | target-partial | incremental-partial | 1.008/0.774 | 4.086/3.397 | 1911.012/1921.012 | 0.523% | valid | 65→24 | 52 | False | 686.8/757.6 |
| main-incremental-v5 | l2svm | lan | 5% | target-partial | incremental-partial | 0.863/0.792 | 3.683/3.206 | 1911.012/1921.012 | 0.523% | valid | 65→24 | 52 | False | 685.2/783.8 |
| main-incremental-v5 | l2svm | wan_heavy | 3% | target-partial | incremental-partial | 0.623/0.836 | 3.488/3.533 | 164108.438/164108.438 | 0.000% | valid | 65→58 | 8 | False | 693.2/733.5 |
| main-incremental-v5 | l2svm | wan_heavy | 5% | target-partial | incremental-partial | 0.866/0.984 | 3.593/3.633 | 164108.438/166108.438 | 1.219% | valid | 65→62 | 4 | False | 682.0/706.9 |
| main-incremental-v5 | l2svm | wan_light | 3% | target-partial | incremental-partial | 0.919/0.790 | 3.373/3.263 | 9232.988/9232.988 | 0.000% | valid | 65→24 | 52 | False | 692.5/780.8 |
| main-incremental-v5 | l2svm | wan_light | 5% | target-partial | incremental-partial | 0.819/0.878 | 3.646/3.728 | 9232.988/9232.988 | 0.000% | valid | 65→51 | 15 | False | 705.1/739.5 |
| main-incremental-v5 | l2svm | wan_mid | 3% | target-partial | incremental-partial | 0.885/1.271 | 3.542/3.535 | 81927.800/81927.800 | 0.000% | valid | 65→58 | 8 | False | 673.1/748.1 |
| main-incremental-v5 | l2svm | wan_mid | 5% | target-partial | incremental-partial | 0.899/0.793 | 3.703/3.310 | 81927.800/82927.800 | 1.221% | valid | 65→62 | 4 | False | 671.2/703.2 |
| main-incremental-v5 | lm | lan | 3% | target-partial | incremental-partial | 0.838/1.178 | 3.083/3.745 | 1703.908/1703.908 | 0.000% | valid | 69→22 | 65 | False | 745.1/824.2 |
| main-incremental-v5 | lm | lan | 5% | target-partial | incremental-partial | 0.604/0.832 | 2.670/3.611 | 1703.908/1703.908 | 0.000% | valid | 69→65 | 4 | False | 747.6/587.4 |
| main-incremental-v5 | lm | wan_heavy | 3% | target-partial | incremental-partial | 0.827/0.824 | 3.143/3.368 | 20282.451/20282.451 | 0.000% | valid | 69→65 | 4 | False | 732.2/619.1 |
| main-incremental-v5 | lm | wan_heavy | 5% | target-partial | incremental-partial | 0.586/0.570 | 2.892/2.935 | 20282.451/20282.451 | 0.000% | valid | 69→65 | 4 | False | 752.6/599.7 |
| main-incremental-v5 | lm | wan_light | 3% | target-partial | incremental-partial | 0.833/0.867 | 3.229/3.194 | 2552.802/2552.802 | 0.000% | valid | 69→65 | 4 | False | 766.0/606.8 |
| main-incremental-v5 | lm | wan_light | 5% | target-partial | incremental-partial | 1.075/0.787 | 3.521/3.400 | 2552.802/2552.802 | 0.000% | valid | 69→65 | 4 | False | 735.4/592.6 |
| main-incremental-v5 | lm | wan_mid | 3% | target-partial | incremental-partial | 0.852/0.567 | 3.435/3.241 | 10951.458/10951.458 | 0.000% | valid | 69→65 | 4 | False | 716.8/585.1 |
| main-incremental-v5 | lm | wan_mid | 5% | target-partial | incremental-partial | 0.601/0.566 | 3.131/3.107 | 10951.458/10951.458 | 0.000% | valid | 69→65 | 4 | False | 740.7/598.9 |
| main-incremental-v5 | logreg | lan | 3% | target-partial | incremental-partial | 1.149/1.312 | 4.714/5.007 | 4407.343/4518.628 | 2.525% | valid | 133→45 | 126 | False | 1136.4/1209.7 |
| main-incremental-v5 | logreg | lan | 5% | target-partial | incremental-partial | 1.003/1.288 | 3.944/4.662 | 4407.343/4518.628 | 2.525% | valid | 133→45 | 126 | False | 1126.0/1213.9 |
| main-incremental-v5 | logreg | wan_heavy | 3% | target-partial | incremental-partial | 1.287/1.332 | 4.977/4.596 | 136798.929/137798.929 | 0.731% | valid | 133→28 | 170 | False | 1141.0/1214.0 |
| main-incremental-v5 | logreg | wan_heavy | 5% | target-partial | incremental-partial | 1.400/1.849 | 4.491/4.762 | 136798.929/137798.929 | 0.731% | valid | 133→28 | 170 | False | 1132.3/1217.6 |
| main-incremental-v5 | logreg | wan_light | 3% | target-partial | incremental-partial | 1.801/1.391 | 5.084/4.661 | 10536.663/10586.663 | 0.475% | valid | 133→28 | 170 | False | 1146.4/1222.9 |
| main-incremental-v5 | logreg | wan_light | 5% | target-partial | incremental-partial | 1.304/1.367 | 4.203/5.662 | 10536.663/10586.663 | 0.475% | valid | 133→28 | 170 | False | 1127.3/1215.5 |
| main-incremental-v5 | logreg | wan_mid | 3% | target-partial | incremental-partial | 1.438/1.346 | 4.592/4.775 | 70360.747/70860.747 | 0.711% | valid | 133→28 | 170 | False | 1153.0/1214.9 |
| main-incremental-v5 | logreg | wan_mid | 5% | target-partial | incremental-partial | 1.171/1.359 | 4.999/4.853 | 70360.747/70860.747 | 0.711% | valid | 133→28 | 170 | False | 1133.4/1218.9 |
| main-incremental-v5 | pca | lan | 3% | target-partial | initial-bound-partial | 0.622/0.597 | 2.598/2.722 | 18742.434/19008.302 | 1.419% | valid | 52→52 | 0 | False | 511.8/514.7 |
| main-incremental-v5 | pca | lan | 5% | target-partial | initial-bound-partial | 0.697/0.463 | 2.605/2.873 | 18742.434/19008.302 | 1.419% | valid | 52→52 | 0 | False | 521.5/500.3 |
| main-incremental-v5 | pca | wan_heavy | 3% | target-partial | incremental-partial | 0.519/0.716 | 2.437/3.034 | 23884.137/23884.137 | 0.000% | valid | 52→27 | 43 | False | 527.9/533.7 |
| main-incremental-v5 | pca | wan_heavy | 5% | target-partial | incremental-partial | 0.514/0.548 | 2.447/2.490 | 23884.137/23884.137 | 0.000% | valid | 52→44 | 14 | False | 545.7/502.4 |
| main-incremental-v5 | pca | wan_light | 3% | target-partial | initial-bound-partial | 0.430/0.559 | 2.343/2.967 | 19070.757/19374.409 | 1.592% | valid | 52→52 | 0 | False | 520.9/512.0 |
| main-incremental-v5 | pca | wan_light | 5% | target-partial | initial-bound-partial | 0.788/0.430 | 2.779/2.723 | 19070.757/19374.409 | 1.592% | valid | 52→52 | 0 | False | 529.7/498.6 |
| main-incremental-v5 | pca | wan_mid | 3% | target-partial | incremental-partial | 0.470/0.558 | 2.822/2.490 | 21278.541/21278.541 | 0.000% | valid | 52→44 | 14 | False | 530.0/524.1 |
| main-incremental-v5 | pca | wan_mid | 5% | target-partial | incremental-partial | 0.604/0.701 | 2.583/2.586 | 21278.541/21576.200 | 1.399% | valid | 52→49 | 5 | False | 526.6/529.1 |
| main-incremental-v5 | sliceline-adult | lan | 3% | target-partial | incremental-partial | 1.402/1.537 | 4.846/5.057 | 514773.381/514773.381 | 0.000% | valid | 108→25 | 127 | False | 1252.4/1271.6 |
| main-incremental-v5 | sliceline-adult | lan | 5% | target-partial | incremental-partial | 1.557/1.661 | 5.756/5.138 | 514773.381/514773.381 | 0.000% | valid | 108→25 | 127 | False | 1254.0/1255.4 |
| main-incremental-v5 | sliceline-adult | wan_heavy | 3% | target-partial | incremental-partial | 1.439/1.534 | 4.652/4.912 | 1333351.423/1333351.423 | 0.000% | valid | 108→25 | 127 | False | 1253.9/1247.1 |
| main-incremental-v5 | sliceline-adult | wan_heavy | 5% | target-partial | incremental-partial | 1.393/2.274 | 4.477/6.021 | 1333351.423/1333351.423 | 0.000% | valid | 108→25 | 127 | False | 1265.4/1263.2 |
| main-incremental-v5 | sliceline-adult | wan_light | 3% | target-partial | incremental-partial | 1.591/1.675 | 5.482/5.753 | 556465.597/556465.597 | 0.000% | valid | 108→25 | 127 | False | 1252.4/1257.4 |
| main-incremental-v5 | sliceline-adult | wan_light | 5% | target-partial | incremental-partial | 1.501/1.751 | 5.545/5.753 | 556465.597/556465.597 | 0.000% | valid | 108→25 | 127 | False | 1262.6/1268.4 |
| main-incremental-v5 | sliceline-adult | wan_mid | 3% | target-partial | incremental-partial | 1.316/2.360 | 5.156/6.894 | 874580.383/874580.383 | 0.000% | valid | 108→25 | 127 | False | 1258.2/1263.8 |
| main-incremental-v5 | sliceline-adult | wan_mid | 5% | target-partial | incremental-partial | 1.368/1.481 | 4.885/4.832 | 874580.383/874580.383 | 0.000% | valid | 108→25 | 127 | False | 1260.8/1258.6 |
| main-incremental-v5 | sliceline-covtype | lan | 3% | target-partial | incremental-partial | 1.956/2.384 | 5.599/6.799 | 676492.438/676492.438 | 0.000% | valid | 108→22 | 130 | False | 1254.9/1253.6 |
| main-incremental-v5 | sliceline-covtype | lan | 5% | target-partial | incremental-partial | 2.269/1.661 | 6.168/5.709 | 676492.438/676492.438 | 0.000% | valid | 108→22 | 130 | False | 1264.3/1262.6 |
| main-incremental-v5 | sliceline-covtype | wan_heavy | 3% | target-partial | incremental-partial | 2.110/2.988 | 5.413/6.378 | 1575667.124/1575667.124 | 0.000% | valid | 108→22 | 130 | False | 1250.5/1255.9 |
| main-incremental-v5 | sliceline-covtype | wan_heavy | 5% | target-partial | incremental-partial | 1.537/1.686 | 5.213/5.413 | 1575667.124/1575667.124 | 0.000% | valid | 108→22 | 130 | False | 1257.8/1269.9 |
| main-incremental-v5 | sliceline-covtype | wan_light | 3% | target-partial | incremental-partial | 1.527/1.585 | 5.350/4.838 | 722561.622/722561.622 | 0.000% | valid | 108→30 | 122 | False | 1252.2/1270.5 |
| main-incremental-v5 | sliceline-covtype | wan_light | 5% | target-partial | incremental-partial | 1.447/1.933 | 5.694/4.995 | 722561.622/722561.622 | 0.000% | valid | 108→30 | 122 | False | 1258.3/1289.2 |
| main-incremental-v5 | sliceline-covtype | wan_mid | 3% | target-partial | incremental-partial | 1.352/1.574 | 4.723/5.217 | 1072070.981/1072070.981 | 0.000% | valid | 108→24 | 128 | False | 1252.7/1258.5 |
| main-incremental-v5 | sliceline-covtype | wan_mid | 5% | target-partial | incremental-partial | 1.454/1.550 | 5.051/5.533 | 1072070.981/1072070.981 | 0.000% | valid | 108→24 | 128 | False | 1265.4/1268.0 |
| main-incremental-v5 | sliceline-kdd98 | lan | 3% | target-partial | incremental-partial | 1.352/1.604 | 4.782/5.088 | 1865362.756/1865362.756 | 0.000% | valid | 108→22 | 130 | False | 1254.0/1250.8 |
| main-incremental-v5 | sliceline-kdd98 | lan | 5% | target-partial | incremental-partial | 1.253/1.677 | 4.784/4.940 | 1865362.756/1865362.756 | 0.000% | valid | 108→22 | 130 | False | 1275.4/1244.1 |
| main-incremental-v5 | sliceline-kdd98 | wan_heavy | 3% | target-partial | incremental-partial | 2.406/2.236 | 5.647/5.620 | 2770027.797/2770027.797 | 0.000% | valid | 108→22 | 130 | False | 1250.2/1273.9 |
| main-incremental-v5 | sliceline-kdd98 | wan_heavy | 5% | target-partial | incremental-partial | 1.370/1.791 | 4.489/5.632 | 2770027.797/2770027.797 | 0.000% | valid | 108→22 | 130 | False | 1274.6/1243.1 |
| main-incremental-v5 | sliceline-kdd98 | wan_light | 3% | target-partial | incremental-partial | 1.690/1.698 | 4.717/5.984 | 1911392.678/1911392.678 | 0.000% | valid | 108→22 | 130 | False | 1264.7/1266.3 |
| main-incremental-v5 | sliceline-kdd98 | wan_light | 5% | target-partial | incremental-partial | 1.291/1.622 | 4.758/6.054 | 1911392.678/1911392.678 | 0.000% | valid | 108→22 | 130 | False | 1261.0/1264.5 |
| main-incremental-v5 | sliceline-kdd98 | wan_mid | 3% | target-partial | incremental-partial | 1.659/1.777 | 6.172/5.655 | 2262815.700/2262815.700 | 0.000% | valid | 108→30 | 122 | False | 1256.8/1251.7 |
| main-incremental-v5 | sliceline-kdd98 | wan_mid | 5% | target-partial | incremental-partial | 1.810/1.651 | 5.962/5.599 | 2262815.700/2262815.700 | 0.000% | valid | 108→30 | 122 | False | 1251.3/1257.6 |
| main-incremental-v5 | sliceline-uscensus | lan | 3% | target-partial | incremental-partial | 1.315/1.764 | 4.962/5.855 | 728244.559/728244.559 | 0.000% | valid | 108→22 | 130 | False | 1261.7/1284.7 |
| main-incremental-v5 | sliceline-uscensus | lan | 5% | target-partial | incremental-partial | 1.409/1.578 | 4.695/4.829 | 728244.559/728244.559 | 0.000% | valid | 108→22 | 130 | False | 1263.8/1259.8 |
| main-incremental-v5 | sliceline-uscensus | wan_heavy | 3% | target-partial | incremental-partial | 1.460/1.672 | 5.189/5.296 | 1707933.240/1707933.240 | 0.000% | valid | 108→28 | 124 | False | 1261.9/1262.5 |
| main-incremental-v5 | sliceline-uscensus | wan_heavy | 5% | target-partial | incremental-partial | 1.325/1.606 | 4.979/4.869 | 1707933.240/1707933.240 | 0.000% | valid | 108→28 | 124 | False | 1259.8/1273.0 |
| main-incremental-v5 | sliceline-uscensus | wan_light | 3% | target-partial | incremental-partial | 1.501/2.154 | 5.307/5.689 | 775913.525/775913.525 | 0.000% | valid | 108→22 | 130 | False | 1269.9/1271.0 |
| main-incremental-v5 | sliceline-uscensus | wan_light | 5% | target-partial | incremental-partial | 1.378/1.961 | 4.703/6.104 | 775913.525/775913.525 | 0.000% | valid | 108→22 | 130 | False | 1270.1/1250.4 |
| main-incremental-v5 | sliceline-uscensus | wan_mid | 3% | target-partial | incremental-partial | 1.483/1.676 | 5.725/4.985 | 1154986.995/1154986.995 | 0.000% | valid | 108→30 | 122 | False | 1254.3/1270.9 |
| main-incremental-v5 | sliceline-uscensus | wan_mid | 5% | target-partial | incremental-partial | 1.436/1.698 | 5.161/5.426 | 1154986.995/1154986.995 | 0.000% | valid | 108→30 | 122 | False | 1243.9/1270.6 |
| main-incremental-v5 | steplm | lan | 3% | target-partial | incremental-partial | 1.134/2.218 | 4.110/5.994 | 1062470168.428/1062470168.428 | 0.000% | valid | 161→18 | 302 | False | 1217.3/1227.0 |
| main-incremental-v5 | steplm | lan | 5% | target-partial | incremental-partial | 1.265/2.117 | 4.947/5.427 | 1062470168.428/1062470168.428 | 0.000% | valid | 161→18 | 302 | False | 1231.4/1214.0 |
| main-incremental-v5 | steplm | wan_heavy | 3% | target-partial | incremental-partial | 1.127/2.237 | 4.398/5.716 | 2990504964.035/2990504964.035 | 0.000% | valid | 161→15 | 310 | False | 1215.6/1234.2 |
| main-incremental-v5 | steplm | wan_heavy | 5% | target-partial | incremental-partial | 1.413/2.275 | 5.212/5.609 | 2990504964.035/2990504964.035 | 0.000% | valid | 161→15 | 310 | False | 1237.5/1220.7 |
| main-incremental-v5 | steplm | wan_light | 3% | target-partial | incremental-partial | 1.665/2.171 | 5.592/5.243 | 1172560896.655/1172560896.655 | 0.000% | valid | 161→18 | 302 | False | 1209.2/1219.8 |
| main-incremental-v5 | steplm | wan_light | 5% | target-partial | incremental-partial | 1.239/3.141 | 4.219/6.674 | 1172560896.655/1172560896.655 | 0.000% | valid | 161→18 | 302 | False | 1220.3/1218.3 |
| main-incremental-v5 | steplm | wan_mid | 3% | target-partial | incremental-partial | 1.238/2.313 | 4.913/5.499 | 1945193993.907/1945193993.907 | 0.000% | valid | 161→15 | 316 | False | 1214.3/1227.3 |
| main-incremental-v5 | steplm | wan_mid | 5% | target-partial | incremental-partial | 1.222/3.573 | 4.205/7.437 | 1945193993.907/1945193993.907 | 0.000% | valid | 161→15 | 316 | False | 1213.6/1230.3 |

## 해석 계약

`target-partial`은 첫 목표 checkpoint에서 `fullyRestored=0`임을 뜻한다. `initial-bound-partial`은 refinement action과 복원 equality가 모두 0인 초기 하한 성공이다. `incremental-partial`은 둘 다 0보다 큰 실제 점진 강화 성공이다. 기존 `partial_incremental_win` 필드는 이제 실제 `incremental-partial`이면서 검증된 paired Global보다 전체 planner 시간이 짧을 때만 참이다. 이전의 모든 partial 승리 의미는 새 `partial_planner_win`에 보존했다. `target-fully-restored`는 목표 도달 시 relaxation이 이미 완전히 복원된 경우이며 partial 성과로 세지 않는다.

`target_reached`는 method checkpoint의 목표 도달을 뜻한다. Oracle 또는 fingerprint가 없으면 그 행은 `pair_verified=false`이고 certificate·regret·승리 집계에는 포함하지 않는다.

Stage 시간(bound control, compact reduction, component preparation/solve, projection, ordered seed, Regional)은 JSON의 `stage_times_at_outcome`에 보존했다. `bound_control_seconds`(`mbe_bound_seconds` 호환 별칭)는 초기 bound와 incremental bound controller의 누적 시간이며 component preparation/solve는 그 안의 하위 시간이다. 따라서 이 세 값을 독립 stage처럼 더하지 않는다. `orderedSeedNanos`가 없는 구현은 0으로 바꾸지 않고 null로 기록한다.

이 결과는 encoded modeled-cost 문제의 인증이다. 실제 workload runtime을 인증하지 않는다.
