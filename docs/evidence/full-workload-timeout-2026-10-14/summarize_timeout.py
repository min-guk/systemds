#!/usr/bin/env python3
"""Read-only JFR summary; exact allocation counter delta, inclusive sample counts."""
import collections,json,sys
from pathlib import Path
p=Path(sys.argv[1]); events=[json.loads(line) for line in p.open()]
samples=[e for e in events if e['type']=='jdk.ExecutionSample' and e.get('thread')=='main']
frames=collections.Counter(); depths=collections.Counter(); leaves=collections.Counter()
for e in samples:
 s=e.get('stack',[]);frames.update(set(f.rsplit(':',1)[0] for f in s))
 depths[sum('MemoOptimizer$ReducedSearch.enumerateCore:' in f for f in s)]+=1
 if s:leaves[s[0].rsplit(':',1)[0]]+=1
alloc=sorted([e for e in events if e['type']=='jdk.ThreadAllocationStatistics' and e.get('thread')=='main'],key=lambda e:e['epoch'])
delta=alloc[-1]['allocated']-alloc[0]['allocated']; span=alloc[-1]['epoch']-alloc[0]['epoch']
weights=collections.Counter(); seen=set(); excluded=[]
for e in sorted([e for e in events if e['type']=='jdk.ObjectAllocationSample'],key=lambda e:e['epoch']):
 t=e.get('threadId')
 if t not in seen:
  seen.add(t);excluded.append({'thread':e.get('thread'),'weight':e['weight'],'reason':'first attach-time sample can include pre-recording allocation; omit from distribution'})
 else:weights[e['objectClass']]+=e['weight']
gc=[e for e in events if e['type']=='jdk.GarbageCollection'];pauses=[e for e in events if e['type']=='jdk.GCPhasePause'];heap=[e for e in events if e['type']=='jdk.GCHeapSummary']
out={'input':str(p),'method':'60-second live-attach JFR profile; inclusive samples overlap, not exact method timers; allocation total from main thread counter delta, not ObjectAllocationSample weights', 'mainSamples':len(samples),'mainFrameCounts':dict(frames),'mainLeafFrames':leaves.most_common(30),'enumerateCoreFrameDepth':dict(depths),'sampleSpanSeconds':max(e['epoch'] for e in samples)-min(e['epoch'] for e in samples),'mainAllocation':{'counterStartBytes':alloc[0]['allocated'],'counterEndBytes':alloc[-1]['allocated'],'deltaBytes':delta,'intervalSeconds':span,'bytesPerSecond':delta/span},'allocationClassWeightEstimatesExcludingFirstPerThread':weights.most_common(20),'excludedInitialAllocationSamples':excluded,'gc':{'collections':len(gc),'pauseSeconds':sum(e['durationSeconds'] for e in pauses),'maxPauseSeconds':max((e['durationSeconds'] for e in pauses),default=0),'maxSampledHeapBytes':max(e['heapUsed'] for e in heap),'maxAfterGcBytes':max(e['heapUsed'] for e in heap if e['when']=='After GC')},'dataLoss':[e for e in events if e['type']=='jdk.DataLoss']}
Path(sys.argv[2]).write_text(json.dumps(out,indent=2)+'\n')
print(json.dumps({k:out[k] for k in ('mainSamples','enumerateCoreFrameDepth','mainAllocation','gc','dataLoss')},indent=2))
for k in ('tryForestTail','enumerateCore','propagate','evaluate','evaluateCost','movementGroupCost','inputCompatible','visitBindingCostContexts'):
 print(k,[(f,n) for f,n in frames.items() if f.endswith('.'+k)])
