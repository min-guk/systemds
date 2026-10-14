#!/usr/bin/env python3
"""Summarize unchanged-JVM profiles. Sample spans are not exact method timers."""
import argparse, collections, json
from pathlib import Path
P = 'org.apache.sysds.hops.fedplanner.memo.'

def phase(stack):
    methods={f.rsplit(':',1)[0] for f in stack}
    has=lambda cls,fn: P+cls+'.'+fn in methods
    if has('MemoProgramModel','optimize'):
        for name, cls, fn in [
            ('selected-cost-audit','MemoProgramModel','evaluateCost'),
            ('supply-reconstruction','MemoProgramModel','reconstructSupplies'),
            ('reduced-core-search','MemoOptimizer$ReducedSearch','enumerateCore'),
            ('search','MemoOptimizer','search'),
            ('leaf-region','MemoOptimizer','tryLeafRegionSolve'),
            ('tree-solve','MemoOptimizer','tryTreeSolve'),
            ('normalization','MemoOptimizer','normalizeAfterPropagation'),
            ('initial-propagation','MemoOptimizer','propagate'),
            ('optimizer-construction','MemoOptimizer','<init>')]:
            if has(cls,fn): return name
        return 'optimizer-other'
    if has('MemoProgramModel','<init>'): return 'model-construction'
    if any(m.startswith(P+'MemoRuleBuilder.') for m in methods): return 'rule-building'
    if has('FederatedPlanMemo','planWithLease'): return 'planner-other'
    return 'outside-planner'

def summarize(path):
    groups={}; counts=collections.Counter(); gc=[]; pauses=[]; heap=[]; cpu=[]; allocstats=[]; tcpu=[]; loss=[]; info=[]
    sample_count=0; allocated=0
    for line in Path(path).open():
        e=json.loads(line); typ=e['type']; counts[typ]+=1
        if typ in ('jdk.ExecutionSample','jdk.ObjectAllocationSample'):
            name=phase(e.get('stack',[]))
            if name not in groups:
                groups[name]={'samples':0,'firstSampleEpoch':None,'lastSampleEpoch':None,'threads':collections.Counter(),
                    'truncatedStacks':0,'inclusiveFrames':collections.Counter(),'leafFrames':collections.Counter(),
                    'allocationEvents':0,'allocationWeightBytes':0,'allocatedClasses':collections.Counter(),
                    'allocationInclusiveFrames':collections.Counter()}
            g=groups[name]; stack=e.get('stack',[]); methods=set(f.rsplit(':',1)[0] for f in stack)
            if typ=='jdk.ExecutionSample':
                sample_count+=1;g['samples']+=1;t=e['epoch']
                g['firstSampleEpoch']=min(g['firstSampleEpoch'],t) if g['firstSampleEpoch'] is not None else t
                g['lastSampleEpoch']=max(g['lastSampleEpoch'],t) if g['lastSampleEpoch'] is not None else t
                g['threads'][e.get('thread')]+=1;g['truncatedStacks']+=e.get('truncated',False)
                g['inclusiveFrames'].update(methods)
                if stack:g['leafFrames'][stack[0].rsplit(':',1)[0]]+=1
            else:
                w=e['weight'];allocated+=w;g['allocationEvents']+=1;g['allocationWeightBytes']+=w
                g['allocatedClasses'][e['objectClass']]+=w
                for f in methods:g['allocationInclusiveFrames'][f]+=w
        elif typ=='jdk.GCHeapSummary':heap.append(e)
        elif typ=='jdk.GarbageCollection':gc.append(e)
        elif typ=='jdk.GCPhasePause':pauses.append(e)
        elif typ=='jdk.CPULoad':cpu.append(e)
        elif typ=='jdk.ThreadAllocationStatistics' and e.get('thread')=='main':allocstats.append(e)
        elif typ=='jdk.ThreadCPULoad' and e.get('thread')=='main':tcpu.append(e)
        elif typ=='jdk.DataLoss':loss.append(e)
        elif typ=='jdk.CPUInformation':info.append(e)
    for g in groups.values():
        a,b=g['firstSampleEpoch'],g['lastSampleEpoch'];g['observedSpanSeconds']=b-a if a is not None else None
        for k,n in [('inclusiveFrames',120),('leafFrames',50),('allocatedClasses',30),('allocationInclusiveFrames',100)]:
            g[k]=g[k].most_common(n)
    return {'input':str(path),'method':'JFR built-in sampling, no instrumentation; inclusive frame counts overlap; allocation weights are estimates',
        'events':dict(counts),'executionSamples':sample_count,'allocationWeightBytes':allocated,'phases':groups,
        'gc':{'collectionCount':len(gc),'totalCollectionDurationSeconds':sum(e['durationSeconds'] for e in gc),
              'stopTheWorldPauseSeconds':sum(e['durationSeconds'] for e in pauses),
              'maxPauseSeconds':max((e['durationSeconds'] for e in pauses),default=0),
              'maxSampledHeapBytes':max((e['heapUsed'] for e in heap),default=0),
              'maxAfterGcHeapBytes':max((e['heapUsed'] for e in heap if e['when']=='After GC'),default=0),
              'causes':dict(collections.Counter(e.get('cause') for e in gc))},
        'threadAllocationStatistics':sorted(allocstats,key=lambda e:e['epoch']),
        'cpuLoad':sorted(cpu,key=lambda e:e['epoch']),'heapTimeline':sorted(heap,key=lambda e:e['epoch']),
        'dataLoss':loss,'cpuInformation':info,'mainThreadCpuLoad':sorted(tcpu,key=lambda e:e['epoch'])}

if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('events');a.add_argument('output');args=a.parse_args()
    Path(args.output).write_text(json.dumps(summarize(args.events),indent=2)+'\n')
