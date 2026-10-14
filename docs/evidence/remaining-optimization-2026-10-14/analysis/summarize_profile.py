#!/usr/bin/env python3
"""Read-only JFR diagnosis; CPU samples and estimated allocation weights are not method timers."""
import collections,json,sys
from pathlib import Path
P='org.apache.sysds.hops.fedplanner.memo.'
def phase(stack):
 methods={f.rsplit(':',1)[0] for f in stack}
 has=lambda cls,method: P+cls+'.'+method in methods
 if has('MemoProgramModel','optimize'):
  for label,cls,method in [('selected-cost-audit','MemoProgramModel','evaluateCost'),('supply-reconstruction','MemoProgramModel','reconstructSupplies'),('elimination-prepare','MemoOptimizer$EliminationPlan','prepare'),('elimination-solve','MemoOptimizer$EliminationPlan','solve'),('legacy-core','MemoOptimizer$ReducedSearch','enumerateCore'),('legacy-search','MemoOptimizer','search'),('leaf-region','MemoOptimizer','tryLeafRegionSolve'),('tree-solve','MemoOptimizer','tryTreeSolve'),('normalization','MemoOptimizer','normalizeAfterPropagation'),('initial-propagation','MemoOptimizer','propagate'),('optimizer-construction','MemoOptimizer','<init>')]:
   if has(cls,method): return label
  return 'optimization-other'
 if has('MemoProgramModel','<init>'): return 'model-construction'
 if any(m.startswith(P+'MemoRuleBuilder.') for m in methods): return 'rule-building'
 if has('MemoProgramFacts','analyze'): return 'facts'
 if any(m.startswith(P+'FederatedPlanMemo.') for m in methods): return 'projection-or-planner-other'
 if any(m.startswith(P) for m in methods): return 'memo-unattributed-stack'
 return 'outside-planner'
def analyze(events_file,process_file):
 events=[json.loads(s) for s in Path(events_file).read_text().splitlines()]
 samples=[e for e in events if e['type']=='jdk.ExecutionSample' and e.get('thread')=='main']
 groups={}; seen=set(); skipped=[]
 for e in sorted(events,key=lambda e:e['epoch']):
  kind=e['type']; stack=e.get('stack',[])
  if kind not in ('jdk.ExecutionSample','jdk.ObjectAllocationSample') or e.get('thread')!='main': continue
  label=phase(stack)
  g=groups.setdefault(label,{'cpuSamples':0,'truncatedStacks':0,'inclusiveFrames':collections.Counter(),'leafFrames':collections.Counter(),'allocationEvents':0,'estimatedAllocationWeightBytes':0,'allocationClasses':collections.Counter(),'allocationInclusiveFrames':collections.Counter()})
  methods={s.rsplit(':',1)[0] for s in stack}
  if kind=='jdk.ExecutionSample':
   g['cpuSamples']+=1; g['truncatedStacks']+=int(e.get('truncated',False)); g['inclusiveFrames'].update(methods)
   if stack:g['leafFrames'][stack[0].rsplit(':',1)[0]]+=1
  elif e.get('threadId') not in seen:
   seen.add(e.get('threadId'));skipped.append({'reason':'exclude initial per-thread weight; may include pre-recording allocations','epoch':e['epoch'],'bytes':e['weight']})
  else:
   w=e['weight'];g['allocationEvents']+=1;g['estimatedAllocationWeightBytes']+=w;g['allocationClasses'][e['objectClass']]+=w
   for f in methods:g['allocationInclusiveFrames'][f]+=w
 for g in groups.values():
  g['cpuSampleFraction']=g['cpuSamples']/len(samples) if samples else None
  for key in ('inclusiveFrames','leafFrames','allocationClasses','allocationInclusiveFrames'):g[key]=g[key].most_common()
 allocation=sorted([e for e in events if e['type']=='jdk.ThreadAllocationStatistics' and e.get('thread')=='main'],key=lambda e:e['epoch'])
 delta=None
 if len(allocation)>=2:
  delta={'firstEpoch':allocation[0]['epoch'],'lastEpoch':allocation[-1]['epoch'],'intervalSeconds':allocation[-1]['epoch']-allocation[0]['epoch'],'deltaBytes':allocation[-1]['allocated']-allocation[0]['allocated']}
 gc=[e for e in events if e['type']=='jdk.GarbageCollection']; pause=[e for e in events if e['type']=='jdk.GCPhasePause']; heap=[e for e in events if e['type']=='jdk.GCHeapSummary']
 process=json.loads(Path(process_file).read_text())['samples']
 return {'sourceEvents':str(events_file),'method':'startup profile JFR, main-thread execution sampling; phases by stack ancestry; inclusive counts overlap; weights estimate allocation not retained memory; no method-time inference','eventCounts':dict(collections.Counter(e['type'] for e in events)),'mainExecutionSamples':len(samples),'phaseGroups':groups,'mainAllocationCounterWindow':delta,'allocationCounterLimitation':'Only one main ThreadAllocationStatistics event at JVM startup; no measured interval delta' if delta is None else None,'mainThreadCpuLoadRaw':[e for e in events if e['type']=='jdk.ThreadCPULoad' and e.get('thread')=='main'],'processCpuScope':'All JVM threads including JIT/GC; launcher OS TID is NOT Java main thread; 100ms RSS samples not exact peak','excludedFirstWeights':skipped,'gc':{'collections':len(gc),'pauseSeconds':sum(e['durationSeconds'] for e in pause),'maxPauseSeconds':max((e['durationSeconds'] for e in pause),default=0),'maxObservedHeapBytes':max((e['heapUsed'] for e in heap),default=None),'maxObservedAfterGcBytes':max((e['heapUsed'] for e in heap if e['when']=='After GC'),default=None)},'process':{'samples':len(process),'pids':sorted({e['pid'] for e in process}),'maxObservedRssBytes':max((e['rssBytes'] for e in process),default=None),'lastObservedProcessCpuSeconds':max((e['processCpuSeconds'] for e in process),default=None),'lastObservedLauncherThreadCpuSeconds':max((e['mainCpuSeconds'] for e in process),default=None)},'dataLoss':[e for e in events if e['type']=='jdk.DataLoss']}
if __name__=='__main__':
 out=analyze(sys.argv[1],sys.argv[2]);Path(sys.argv[3]).write_text(json.dumps(out,indent=2)+'\n')
 print(json.dumps({k:v for k,v in out.items() if k not in ('phaseGroups','excludedFirstWeights')},indent=2))
 for phase_name,g in sorted(out['phaseGroups'].items(),key=lambda item:-item[1]['cpuSamples']):
  print(phase_name,g['cpuSamples'],f"{100*g['cpuSampleFraction']:.1f}%",'alloc-weight-MB',round(g['estimatedAllocationWeightBytes']/1e6,2))
  print('memo inclusive',[(f,n) for f,n in g['inclusiveFrames'] if f.startswith(P)][:18])
  print('leaf',g['leafFrames'][:8]);print('classes',g['allocationClasses'][:6])
