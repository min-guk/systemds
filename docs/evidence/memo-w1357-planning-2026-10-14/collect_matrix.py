from pathlib import Path
import collections,csv,hashlib,json,re,shutil
ROOT=Path(__file__).resolve().parent
BASE=Path('/home/mchoi/memo-w1357-planning-20261014-c26-once')
DEP=Path('/home/mchoi/memo-w1357-planning-20261014-c26-dependency-once')
ENV=Path('/grid/3/cofee-lm-sweep-mchoi-20260914/memo-w1357-planning-20261014-c26-once')
REPO=ROOT.parents[2]
def dump(p,v):p.write_text(json.dumps(v,indent=2,sort_keys=True)+'\n')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def clean(t):return re.sub(r'\x1b\[[0-9;]*m','',t)
def copy_batch(source,dest):
 dest.mkdir(exist_ok=True)
 for p in source.glob('*'):
  if p.is_file() and p.suffix in {'.json','.csv'}:shutil.copy2(p,dest/p.name)
 for a in (source/'attempts').iterdir():
  d=dest/'attempts'/a.name;d.mkdir(parents=True,exist_ok=True)
  for p in a.iterdir():
   if p.is_file() and p.suffix in {'.json','.log','.xml','.dml','.sh','.rc'}:shutil.copy2(p,d/p.name)
copy_batch(BASE,ROOT/'initial56');copy_batch(DEP,ROOT/'dependency12')
s=json.loads((BASE/'summary.json').read_text());ds=json.loads((DEP/'summary.json').read_text())
assert s['complete'] and s['attemptedCells']==56 and ds['complete'] and ds['attemptedCells']==12
replacement={x['cell']['id']:x for x in ds['rows']}
rows=[]
for raw in s['rows']:
 key=raw['cell']['id'];new=replacement.get(key,raw);batch='dependency12' if key in replacement else 'initial56'
 name=f"{new['cell']['suite']}-{new['cell']['workload']}-w{new['cell']['workers']}"
 p=ROOT/batch/'attempts'/name;t=clean((p/'coordinator.log').read_text(errors='replace'))
 if key in replacement:
  old=clean((ROOT/'initial56'/'attempts'/name/'coordinator.log').read_text(errors='replace'))
  assert 'ParseException' in old and '[MemoPlannerPhase]' not in old
  assert (ROOT/'initial56'/'attempts'/name/'cell.dml').read_bytes()==(p/'cell.dml').read_bytes()
  assert (ROOT/'initial56'/'attempts'/name/'execution.xml').read_bytes()==(p/'execution.xml').read_bytes()
 if new['status']=='passed':outcome='OPTIMAL'
 elif new['failureCategory']=='timeout_not_proven':outcome='OPTIMIZER_TIMEOUT'
 elif 'UNSUPPORTED_PARFOR' in t:outcome='UNSUPPORTED_PARFOR'
 elif 'UNRESOLVED_COST_CONTEXT' in t:outcome='UNRESOLVED_COST_CONTEXT'
 elif 'UNKNOWN_SIZE' in t:outcome='UNKNOWN_SIZE'
 elif 'AMBIGUOUS_FED_EXECUTION_LAYOUT' in t:outcome='AMBIGUOUS_EXECUTION_LAYOUT'
 elif 'violates canonical ingress' in t:outcome='CANONICAL_INGRESS_ERROR'
 elif 'Memo planner did not prove an optimum: UNRESOLVED' in t:outcome='UNRESOLVED'
 elif 'Memo planner did not prove an optimum: INFEASIBLE' in t:outcome='INFEASIBLE'
 elif 'NullPointerException' in t:outcome='UNLOCALIZED_NULL_POINTER'
 else:outcome='OTHER_FAILURE'
 phases=[{'phase':a,'elapsedNs':int(b)} for a,b in re.findall(r'\[MemoPlannerPhase\] phase=(\w+) elapsedNs=(\d+)',t)]
 error=t.split('An Error Occurred :',1)[1].strip() if 'An Error Occurred :' in t else None
 if outcome=='OPTIMAL':
  assert new['compileOnlyVerified'] and new['reportedWorkerCountVerified']
  assert len(new['memoPlanning'])==len(new['memoSearch'])==1
  assert new['memoSearch'][0]['status']=='OPTIMAL' and new['timing']['executionSeconds']==0
 if outcome=='OPTIMIZER_TIMEOUT':
  assert new['returncode']==124 and [v['phase'] for v in phases]==['facts','rules','model']
 rows.append({**new,'selectedBatch':batch,'artifactDirectory':str(p.relative_to(ROOT)),
              'observedOutcome':outcome,'completedPhaseRecords':phases,'observedError':error})
assert len(rows)==len({r['cell']['id'] for r in rows})==56
summary={'schema':'memo-w1357-combined-evaluation-v1','productionCommit':'802cd1381f38796f61e71956d0730728d44837ae',
         'initialRunnerCommit':'1a8c478775','dependencyRunnerCommit':'762b8af64e','expectedCells':56,'complete':True,
         'successfulCells':sum(r['status']=='passed' for r in rows),'outcomes':dict(collections.Counter(r['observedOutcome'] for r in rows)),
         'selectionPolicy':'44 nondependency original attempts + 12 dependency attempts after cwd-only environment repair; prior failures preserved',
         'excludedEnvironmentAttempts':{'gridSetupBeforeJvm':56,'relativeLibraryParseBeforeMemo':12},'rows':rows}
dump(ROOT/'evaluation.json',summary)
fields=['workload','workers','outcome','memoPlanningSeconds','fullInitialPlanningSeconds','fullCompileSeconds','failureOrCompletionWallSeconds','artifactDirectory']
with (ROOT/'results.csv').open('w',newline='') as f:
 w=csv.DictWriter(f,fieldnames=fields);w.writeheader()
 for r in rows:
  tm=r['timing'];w.writerow({'workload':r['cell']['suite']+':'+r['cell']['workload'],'workers':r['cell']['workers'],
   'outcome':r['observedOutcome'],'memoPlanningSeconds':tm.get('memoPlanningSeconds'),'fullInitialPlanningSeconds':tm.get('fullInitialPlanningSeconds'),
   'fullCompileSeconds':tm.get('fullCompileSeconds'),'failureOrCompletionWallSeconds':tm['harnessWallSeconds'],'artifactDirectory':r['artifactDirectory']})
env=json.loads((ENV/'summary.json').read_text());assert len(env['rows'])==56 and not list(ENV.rglob('coordinator.log'))
dump(ROOT/'environment-attempts.json',{'gridRoot':str(ENV),'gridAttemptCount':56,'gridJvmCount':0,
 'gridSummarySha256':sha(ENV/'summary.json'),'gridError':(ENV/'attempts/ml-pca-w1/docker.log').read_text(),
 'relativeLibraryParseCount':12,'preservedIn':'initial56/attempts; original full grid root also remains'})
exp=REPO/'experiments/memo-w1357-planning-20261014'
for p in exp.iterdir():
 if p.is_file() and p.suffix in {'.log','.json','.txt'}:shutil.copy2(p,ROOT/p.name)
# Report tables: success durations and failure wall are deliberately separate.
workloads=[]
for r in rows:
 k=(r['cell']['suite'],r['cell']['workload'])
 if k not in workloads:workloads.append(k)
code={'OPTIMAL':'성공','OPTIMIZER_TIMEOUT':'T','UNSUPPORTED_PARFOR':'P','UNKNOWN_SIZE':'S','UNRESOLVED':'U','INFEASIBLE':'I','UNRESOLVED_COST_CONTEXT':'C','UNLOCALIZED_NULL_POINTER':'N','AMBIGUOUS_EXECUTION_LAYOUT':'A','CANONICAL_INGRESS_ERROR':'Q'}
table=['| Workload | W1 | W3 | W5 | W7 |','|---|---:|---:|---:|---:|']
for suite,name in workloads:
 values=[]
 for n in (1,3,5,7):
  r=next(r for r in rows if r['cell']['suite']==suite and r['cell']['workload']==name and r['cell']['workers']==n)
  tm=r['timing'];o=r['observedOutcome']
  values.append(f"성공 {tm['memoPlanningSeconds']:.3f}s" if o=='OPTIMAL' else ('T 300s 제한' if o=='OPTIMIZER_TIMEOUT' else f"{code.get(o,'E')} ({tm['harnessWallSeconds']:.3f}s wall)"))
 table.append('| '+suite+':'+name+' | '+' | '.join(values)+' |')
reason={'pca':'EIGEN multi-return output가 UNKNOWN -1×-1이어서 정확한 비용을 만들지 못함.',
 'als':'W1은V/TRead -1×-1로 UNKNOWN_SIZE. W3/W5/W7은wdivmm의[ROW,LOCAL,COL,LOCAL] FED/LOCAL 실행layout을하나로증명하지못해 AMBIGUOUS_FED_EXECUTION_LAYOUT.',
 'kmeans':'UNSUPPORTED_PARFOR; 현재Memo는sequential loop만지원.',
 'lm':'UNRESOLVED; solve의NATIVE_CATALOG_NOT_IMPLEMENTED/COVERAGE_GAP. privacy-pruned진단도함께출력.',
 'logreg':'facts/rules/model완료후model.optimize()에서coordinator300초deadline. 완료된optimizer/Memo총시간없음.',
 'l2svm':'보고된INFEASIBLE; scalarINT64↔FP64 LOOP_CARRIED_SHAPE_MISMATCH와 MOVEMENT_FACTOR_WIDTH width11. 실제workload의수학적불가능을의미한다고단정하지않음.',
 'steplm':'중첩call/loopphase의UNRESOLVED_COST_CONTEXT.',
 'glm':'facts/rules/model완료후model.optimize()에서coordinator300초deadline. 완료된optimizer/Memo총시간없음.',
 'gnmf':'OPTIMAL+RuntimeProgram구성/compile완료,worker수일치,executionSeconds=0.',
 'gmm':'NullPointerException: Cannot invoke java.lang.Double.doubleValue(). 완료된phase/stack이없어컴파일러와Memo중정확한발생위치는미확정.',
 'P1_FULL':'relative source환경복구후 Memo facts UNSUPPORTED_PARFOR(region54).',
 'P2_PREP':'W1/W5는nrow의X/TRead -1×-1로 UNKNOWN_SIZE. W3/W7은canonical ingress 위반(각workerindex1/5). 원P2public-recode메타데이터opt-in은유지했고ranges/workerbinding을변경하지않음.',
 'ADULT':'relative source환경복구후 UNSUPPORTED_PARFOR(region8).',
 'COVTYPE':'relative source환경복구후 UNSUPPORTED_PARFOR(region8).'}
report='''# FedPlanner Memo C26: 전체 workload W1/3/5/7 planning 결과\n\n## 결론\n\n**56조건 중4조건만성공했다. GNMF는W1/W3/W5/W7 모두OPTIMAL이며,나머지workload는현재C26에서완료된플랜을확인하지못했다. 전체workload가planning가능하다고보고할수없다.**\n\nProduction은GitHubmain에게시된C26 `802cd1381f` 그대로다. 어댑터 `1a8c478775`,sealed라이브러리CWD환경수정 `762b8af64e`만추가했으며Java planner/Oracle/privacy/cost semantics는변경하지않았다.\n\n## 조건별 결과\n\n성공행의시간은**Memo5단계합**이다. 괄호의wall은실패까지Docker/JVM시작을포함한외부시간이며planning완료시간이아니다. T는coordinator300초제한이고실제Memo총시간은측정완료되지않았다.\n\n'''+ '\n'.join(table)+'''\n\n- P:PARFOR미지원; S:정확한shape/cost정보부족; A:실행layout모호성; Q:canonical ingress위반; U:UNRESOLVED; I:보고된INFEASIBLE; C:unresolvedcostcontext; N:위치미확정NPE; T:timeout(불가능증명아님).\n- 실패행에서Memo/fullcompile시간은null이며wall이나부분phase합으로대체하지않았다.\n\n## 성공한GNMF의시간 구분\n\n| Workers | Memo planning | 전체 initial planning | 전체 compile | 외부 wall |\n|---|---:|---:|---:|---:|\n'''
for r in rows:
 if r['status']=='passed':
  t=r['timing'];report+=f"| {r['cell']['workers']} | {t['memoPlanningSeconds']:.9f}s | {t['fullInitialPlanningSeconds']:.9f}s | {t['fullCompileSeconds']:.6f}s | {t['harnessWallSeconds']:.6f}s |\n"
report+='\n## 실패 원인 (로그에서 관측)\n\n'
for suite,name in workloads:
 r=next(r for r in rows if r['cell']['suite']==suite and r['cell']['workload']==name and r['cell']['workers']==1)
 report+=f"- **{suite}:{name}**: {reason[name]} [W1 원로그](evidence/memo-w1357-planning-2026-10-14/{r['artifactDirectory']}/coordinator.log).\n"
report+='''\n## 실행 범위와 정확성 한계\n\n- ML10+P1/P2+SliceLineADULT/COVTYPE,worker1/3/5/7의봉인된DML/ranges/metadata/privacy/seed를사용했다. ML은50000×128이며앞선192×8 tinyE2E와다르다. 따라서기존LogReg3.028초/GLM5.072초와동일입력성능비교가아니다.\n- pinnedDockerimage sha256:2816d74…,4CPU/8GiB,coordinatorheap3GiB/Active4,nativeBLASnone/codegenfalse,C26productiondefault비용(추가envoverride없음). 과거dynamicMKL/LANcalibration비교로주장하지않는다.\n- compile-only는RuntimeProgram구성까지이며training은실행하지않았다. GNMF의성공은planning/compile성공이지학습결과정확성검증이아니다. 실제binarydata디렉터리/블록은봉인stage에존재하지만runtimepayload읽기나workerJVM/RPC는수행하지않았다.\n- 성공4건은각1개의OPTIMAL Memo/Searchreceipt,요청worker수일치,전체compile/RuntimeProgramreceipt,executionSeconds0을확인했다. timeout8건은facts/rules/model완료후optimizer가끝나지않았고정확한optimizer소요시간은없다.\n- DMLScript가일부오류를출력하고exit0으로끝내므로exitcode만으로성공판정하지않았다. 원rawcategory evidence_validation_failure36건도실제오류로그와완료receipt부재로실패다. raw결과는그대로보존하고observedOutcome을별도추가했다.\n- GMM예외의정확한발생위치는stack없이확정하지않았다. L2SVM의INFEASIBLE도현재모델보고상태이며실제알고리즘의수학적실행불가능을뜻한다고단정하지않았다.\n\n## 환경 복구와 실행 횟수 (숨기지 않음)\n\n1. /grid직접bind의첫56환경시도는SnapDocker에서script를찾지못해JVM시작0회였다. 원artifact는보존했다.\n2. /home실행56조건중P1/SliceLine12는relative source라이브러리CWD가잘못돼ParseException으로Memo에진입하지못했다. 이원본도보존했다.\n3. sealed원실험CWD1행만수정해그12조건을실행했다. 44나머지조건은재실행하지않았다. 최종표는44원조건+12환경수정조건으로구성했다. 동일조건에서완료된Memo성능측정의재시도는없다.\n4. 원DML/config/ranges/privacy/Java/자원은같고,수정된것은상대라이브러리를찾는CWD뿐이다. 원결과를덮어쓰지않았다.\n\n## 검증·산출물\n\n- productionJava는C26동결12707파일과동일,하네스unit12/12/syntax/diffcheckPASS,독립입력/결과reviewPASS.\n- 352주소·worker/range위치,187distinctmetadata파일,1721실제binaryblock이봉인receipt와일치했다.\n- [통합JSON](evidence/memo-w1357-planning-2026-10-14/evaluation.json), [CSV](evidence/memo-w1357-planning-2026-10-14/results.csv), [수집/비교스크립트](evidence/memo-w1357-planning-2026-10-14/collect_matrix.py).\n- `initial56`과`dependency12`에원command/DML/config/receipt/log/rc를분리보존한다. `environment-attempts.json`은사전환경실패를기록한다.\n\n## 후속 우선순위 (이번 작업에서는수정하지않음)\n\n1. 실제fullLogReg/GLM optimizertimeout을작은E2E와분리해프로파일링한다.\n2. PARFOR facts/support범위,KMeans/P1/SliceLine을지원할지명시적으로설계한다.\n3. PCA/EIGEN multi-return,ALS/P2의shape/크기추론,StepLMcostcontext와LMsolvecatalog공백을닫는다.\n4. ALS의FEDexecutionlayout,P2의worker/rangecanonicalingress,L2SVMscalarlooptype/넓은movementfactor와GMMNPE를별도회귀fixture로재현·분석한다.\n\n이번검증을통과시키기위해UNKNOWN/DEFER,privacy,feasibility,workerbinding을생략하거나runtimefallback을넣지않았다.\n'''
(REPO/'docs/FEDPLANNER_MEMO_W1357_PLANNING_MATRIX_RESULT_2026-10-14_KO.md').write_text(report)
dump(ROOT/'artifacts-sha256.json',{str(p.relative_to(ROOT)):sha(p) for p in sorted(ROOT.rglob('*')) if p.is_file() and p.name!='artifacts-sha256.json'})
print('successful',summary['successfulCells'],'outcomes',summary['outcomes'],'artifacts',len(list(ROOT.rglob('*'))))
