#!/usr/bin/env python3
"""Build the authenticated, no-duplicate interim 284-cell result table."""
from __future__ import annotations
import csv,hashlib,json,math,pathlib,statistics,sys
PLANNERS=('DP','FedAll','Heuristic','MinST')
WORKLOADS=('kmeans','pca','lm','l2svm','logreg','als','steplm')
PROFILES=('lan','wan_light','wan_mid')
def canonical(v): return json.dumps(v,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode()
def sha(p): return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def read(p): return json.loads(pathlib.Path(p).read_text())
def parse(cell):
 p=dict(x.split('=',1) for x in cell.split('|'))
 if set(p)!={'workers','planner','workload','profile'}: raise ValueError(f'non-canonical cell: {cell}')
 return p
def verify_desc(path,schema):
 d=read(path)
 if d.get('schema')!=schema: raise ValueError(f'schema mismatch: {path}')
 claimed=d.pop('descriptor_sha256',None)
 if claimed!=hashlib.sha256(canonical(d)).hexdigest(): raise ValueError(f'descriptor mismatch: {path}')
 d['descriptor_sha256']=claimed; return d
def verify_row(row,cell,response_sha):
 if row['cell']!=cell or row['response_sha256']!=response_sha: raise ValueError(f'row binding mismatch: {cell}')
 if row['attempt']!=1 or row['oracle_passed'] is not True or row['fallback'] is not False: raise ValueError(f'row contract: {cell}')
def verify_lifecycle(row,response_path):
 response=read(response_path); claimed=response.pop('descriptor_sha256',None)
 if claimed!=hashlib.sha256(canonical(response)).hexdigest(): raise ValueError(f'response descriptor: {row["cell"]}')
 bundle=pathlib.Path(row['bundle']); metric=read(bundle/'metric.json'); oracle=read(bundle/'semantic_oracle.json'); scan=read(bundle/'scan.json')
 sec=metric.get('seconds')
 if (response.get('success') is not True or response.get('teardown_zero_resources') is not True
  or response.get('coordinator_restart_count')!=0 or response.get('worker_restart_count')!=0
  or oracle.get('passed') is not True or any(scan.get(k) is not False for k in ('error','fallback','resource_invalid','timeout'))
  or isinstance(sec,bool) or not isinstance(sec,(int,float)) or not math.isfinite(sec) or sec<=0
  or sec!=row['execution_seconds']): raise ValueError(f'lifecycle proof: {row["cell"]}')
def mat(row,prov):
 p=parse(row['cell'])
 return {'cell':row['cell'],'workers':int(p['workers']),'planner':p['planner'],'workload':p['workload'],'profile':p['profile'],
 'execution_seconds':float(row['execution_seconds']),'full_lifecycle_seconds':float(row['full_lifecycle_seconds']),
 'attempt':int(row['attempt']),'oracle_passed':bool(row['oracle_passed']),'fallback':bool(row['fallback']),
 'response_sha256':row['response_sha256'],**prov}
def main():
 if len(sys.argv)!=3: raise SystemExit('usage: build_interim_dataset.py CAMPAIGN_ROOT OUTPUT_DIR')
 root=pathlib.Path(sys.argv[1]).resolve(); out=pathlib.Path(sys.argv[2]).resolve(); out.mkdir(parents=True,exist_ok=True)
 registry_path=root/'base-completed.json'; registry=verify_desc(registry_path,'g007-completed-cell-registry/v1')
 manifest=read(root/'planners/MinST/manifest.json'); launch=root/'control/launch-receipt.json'; snapshot=out/'continuation_rows_snapshot_1.jsonl'
 rows=[]
 for e in registry['cells']:
  rp=pathlib.Path(e['rows_path']); lines=rp.read_text().splitlines(); row=json.loads(lines[e['row_number']-1]); response=pathlib.Path(e['response_path']); actual=sha(response)
  if actual!=e['response_sha256']: raise ValueError(f'response hash: {e["cell"]}')
  verify_row(row,e['cell'],actual); verify_lifecycle(row,response)
  rows.append(mat(row,{'source_kind':e['source_kind'],'campaign_root':e['campaign_root'],'stage':e['stage'],
   'systemds_commit':e['systemds_commit'],'systemds_jar_sha256':e['systemds_jar_sha256'],'response_path':str(response)}))
 new=[json.loads(x) for x in snapshot.read_text().splitlines() if x]
 if len(new)!=1: raise ValueError('snapshot must contain exactly one newly completed continuation row')
 for row in new:
  bundle=pathlib.Path(row['bundle']); response=bundle.parents[2]/'response.json'; actual=sha(response)
  verify_row(row,row['cell'],actual); verify_lifecycle(row,response)
  rows.append(mat(row,{'source_kind':'e18d326-unfinished-only-continuation-success-prefix','campaign_root':str(root),
   'stage':manifest['stage_descriptor'].removesuffix('/stage-descriptor.json'),'systemds_commit':manifest['systemds_commit'],
   'systemds_jar_sha256':manifest['systemds_jar_sha256'],'response_path':str(response)}))
 by={x['cell']:x for x in rows}
 if len(rows)!=284 or len(by)!=284 or registry['completed_cells']!=283: raise ValueError(f'cardinality rows={len(rows)} unique={len(by)} base={registry["completed_cells"]}')
 rows.sort(key=lambda x:(PLANNERS.index(x['planner']),x['workers'],WORKLOADS.index(x['workload']),PROFILES.index(x['profile'])))
 csvp=out/'authenticated_rows_284.csv'; fields=list(rows[0])
 with csvp.open('w',newline='') as f:
  w=csv.DictWriter(f,fieldnames=fields,lineterminator='\n'); w.writeheader(); w.writerows(rows)
 counts={p:sum(x['planner']==p for x in rows) for p in PLANNERS}
 matched={}
 for x in rows: matched.setdefault((x['workers'],x['workload'],x['profile']),{})[x['planner']]=x['execution_seconds']
 matched={k:v for k,v in matched.items() if set(v)==set(PLANNERS)}
 def passes(v,t): return v['MinST']<=v['DP']*(1+t) and v['DP']<=v['Heuristic']*(1+t) and v['DP']<=v['FedAll']*(1+t)
 ratios={p:[v[p]/v['DP'] for v in matched.values()] for p in PLANNERS}
 summary={'schema':'g007-interim-authenticated-performance/v1','status':'interim-incomplete-not-final','completed_unique_cells':284,
 'canonical_total_cells':336,'remaining_cells':52,'counts_by_planner':counts,'matched_four_planner_cells':len(matched),
 'ordering_contract':'MinST <= DP and DP <= Heuristic and DP <= FedAll',
 'ordering_exact_passes':sum(passes(v,0) for v in matched.values()),'ordering_exact_failures':sum(not passes(v,0) for v in matched.values()),
 'ordering_5pct_passes':sum(passes(v,.05) for v in matched.values()),'ordering_5pct_failures':sum(not passes(v,.05) for v in matched.values()),
 'median_runtime_ratio_to_dp':{p:statistics.median(v) for p,v in ratios.items()},
 'minimum_execution_seconds':min(x['execution_seconds'] for x in rows),'maximum_execution_seconds':max(x['execution_seconds'] for x in rows),
 'provenance_warning':'Stitched no-duplicate Docker successes span multiple committed binaries; this interim view is diagnostic and is not a homogeneous final performance run.',
 'base_registry_sha256':sha(registry_path),'base_registry_descriptor_sha256':registry['descriptor_sha256'],
 'continuation_snapshot_rows':1,'continuation_snapshot_sha256':sha(snapshot),'launch_receipt_sha256':sha(launch),'csv_sha256':sha(csvp)}
 (out/'summary.json').write_text(json.dumps(summary,indent=2,sort_keys=True)+'\n'); print(json.dumps(summary,indent=2,sort_keys=True))
if __name__=='__main__': main()
