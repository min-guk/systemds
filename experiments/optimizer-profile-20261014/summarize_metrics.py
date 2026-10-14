#!/usr/bin/env python3
"""Compact comparison of final JFR files; no timing from live dump is mixed in."""
import collections,csv,json,re,sys
from pathlib import Path
from analyze_jfr import P,phase
ROOT=Path(__file__).resolve().parent
METHODS=['MemoProgramModel.movementGroupCost','MemoProgramModel.visitBindingCostContexts',
'MemoShapes.firstUnfixedCostGuardForBinding','MemoShapes.firstUnfixedCostGuard',
'MemoShapes.provesRepeatHeadCostStats','MemoShapes.withPhase','MemoShapes.phaseProvesGuard',
'MemoProgramFacts.guardValue','MemoProgramFacts.guardValueProvenance','MemoProgramFacts.guardDecision',
'MemoShapes.viewAt','MemoShapes.resolvedBindingShape','MemoShapes$PhaseContextKey.<init>',
'MemoProgramModel.entryCost','MemoProgramModel.inputCompatible','MemoProgramModel.privacyCompatible',
'MemoProgramModel.visitActive','MemoOptimizer.propagate','MemoOptimizer.support',
'MemoOptimizer.evaluate','MemoOptimizer.computeConditionalCostBound','MemoOptimizer$TupleKey.<init>',
'MemoOptimizer.stamp','MemoOptimizer$CostTerm.evaluateExact','MemoOptimizer.foldAndGroupCosts']

def compact(name):
    p=json.loads((ROOT/(name+'-profile.json')).read_text())
    n=0;weight=0; c=collections.Counter();a=collections.Counter();disjoint=collections.Counter();ad=collections.Counter()
    first=None;last=None
    for line in (ROOT/(name+'-events.jsonl')).open():
        e=json.loads(line)
        if e['type'] not in ('jdk.ExecutionSample','jdk.ObjectAllocationSample'):continue
        methods={f.rsplit(':',1)[0] for f in e.get('stack',[])}
        if P+'MemoProgramModel.optimize' not in methods:continue
        is_sample=e['type']=='jdk.ExecutionSample'
        if is_sample:
            n+=1;first=min(first,e['epoch']) if first is not None else e['epoch'];last=max(last,e['epoch']) if last is not None else e['epoch']
        else:weight+=e['weight']
        for m in METHODS:
            if P+m in methods:
                if is_sample:c[m]+=1
                else:a[m]+=e['weight']
        category='solver-and-other'
        for label,m in [('movement','MemoProgramModel.movementGroupCost'),('entry','MemoProgramModel.entryCost'),('privacy','MemoProgramModel.privacyCompatible'),('input-legality','MemoProgramModel.inputCompatible')]:
            if P+m in methods:category=label;break
        if is_sample:disjoint[category]+=1
        else:ad[category]+=e['weight']
    phases={}
    for k,v in p['phases'].items():
        if k in ['outside-planner','rule-building','model-construction','planner-other']:continue
        st,en=v['firstSampleEpoch'],v['lastSampleEpoch']
        cp=[e for e in p['cpuLoad'] if st is not None and st<=e['epoch']<=en]
        heap=[e for e in p['heapTimeline'] if st is not None and st<=e['epoch']<=en]
        load=sum(e['jvmUser']+e['jvmSystem'] for e in cp)/len(cp) if cp else None
        phases[k]={'samples':v['samples'],'samplePercentOfOptimize':100*v['samples']/n,
          'firstSampleEpoch':st,'lastSampleEpoch':en,'observedSpanSeconds':v['observedSpanSeconds'],
          'allocationWeightGiB':v['allocationWeightBytes']/2**30,'cpuLoadRawMean':load,
          'estimatedProcessCores':load*48 if load is not None else None,
          'gcHeapSampleMaxMiB':max((e['heapUsed']/2**20 for e in heap),default=None)}
    return {'optimizeExecutionSamples':n,'sampledOptimizeSpanSeconds':last-first,
      'inclusiveMethods':{m:{'samples':c[m],'samplePercent':100*c[m]/n,'allocationWeightGiB':a[m]/2**30} for m in METHODS},
      'disjointSampleCategories':dict(disjoint),'disjointAllocationGiB':{k:v/2**30 for k,v in ad.items()},
      'optimizeAllocationWeightGiB':weight/2**30,'phases':phases,'wholeRecordingGc':p['gc'],
      'dataLossEvents':p['dataLoss']}

if __name__=='__main__':
    names=sys.argv[1:] or ['baseline-logreg','current-logreg','current-glm']
    result={'schema':'memo-optimizer-profile-v1','interpretation':{
       'samples':'inclusive stack presence, not invocation counts or exact method CPU timers',
       'spans':'first-to-last execution sample within one phase; lower observation spans, not exact entry-exit duration',
       'allocation':'JFR weighted sampling estimate, not retained heap/RSS',
       'cpu':'JFR CPULoad mean multiplied by observed host hwThreads=48; approximate cores, not container quota percent',
       'policy':'one workload run per frozen variant/case; no production implementation changes'},
       'profiles':{name:compact(name) for name in names}}
    (ROOT/'profile-comparison.json').write_text(json.dumps(result,indent=2)+'\n')
