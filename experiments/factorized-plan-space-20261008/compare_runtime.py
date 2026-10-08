#!/usr/bin/env python3
"""Compare pinned Docker outputs and per-coordinator observations; never infer peak heap from RSS."""
import argparse,json,pathlib,re
p=argparse.ArgumentParser();p.add_argument('baseline',type=pathlib.Path);p.add_argument('after',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
def summarize(root):
 data=json.loads((root/'result.json').read_text());cases={}
 for c in data['cases']:
  row={'passed':c['passed'],'cpReturncode':c.get('cpReturncode'),'fedReturncode':c.get('fedReturncode'),'cpFingerprint':c.get('cpFingerprint'),'fedFingerprint':c.get('fedFingerprint'),'requiredWeightedKernels':c.get('requiredWeightedKernels')}
  proof=c.get('canonicalProof',{}).get('receipt',{}); canonical=proof.get('canonicalProof',{})
  row.update({k:proof.get(k) for k in ['analysisFingerprint','normalizedPlanFingerprint','compileNanos','executionNanos','coordinatorMemory']});row['objectiveBits']=canonical.get('canonicalObjectiveBits');row['modelCounts']=canonical.get('modelCounts');row['costFingerprint']=canonical.get('reconstructedCostSurfaceFingerprint')
  row['selectionEvidence']={k:proof.get(k) for k in ['selectedCandidateSelections','selectedRelocations','selectedLocalMaterializations','sharedSupplyLifetimes','runtimeFallbackCount','runtimeRepairCount']}
  row['selectedOccurrences']=proof.get('selectedOccurrences')
  row['assignment']=canonical.get('assignment')
  log=root/'cases'/c['case']/'fed.log'; matches=re.findall(r'CandidateE2EReceipt (.*)',log.read_text()) if log.exists() else []
  row['planning']=dict(v.split('=',1) for v in matches[-1].split()) if matches else {}
  audit=list((root/'audit'/f"{c['case']}-fed").glob('candidate-space-*.jsonl'))
  if audit:
   rows=[json.loads(line) for path in audit for line in path.read_text().splitlines()]; families=[r for r in rows if r.get('representation')=='FACTORIZED_CP'];row['candidateAudit']={'rows':len(rows),'familyHeaders':len(families),'familyLogicalTuples':sum(int(r['logicalTuples']) for r in families),'availableExplicitRows':sum(r.get('representation')!='FACTORIZED_CP' and r.get('publishedRule',{}).get('status')=='AVAILABLE' for r in rows)}
  cases[c['case']]=row
 return {'status':data['status'],'run':str(root),'cases':cases}
b=summarize(a.baseline);n=summarize(a.after);comparison={}
for name,before in b['cases'].items():
 after=n['cases'][name]
 def present_equal(key):
  return before[key]==after[key] if before[key] is not None and after[key] is not None else None
 comparison[name]={'bothPassed':before['passed'] and after['passed'],'outputEqual':present_equal('fedFingerprint'),'objectiveBitsEqual':present_equal('objectiveBits'),'normalizedPlanEqual':present_equal('normalizedPlanFingerprint'),'analysisFingerprintEqual':present_equal('analysisFingerprint'),'costFingerprintEqual':present_equal('costFingerprint')}
 if before['objectiveBits'] is not None and after['objectiveBits'] is not None:
  comparison[name]['exactSelectionEvidenceEqual']=before['selectionEvidence']==after['selectionEvidence']
  comparison[name]['assignmentEqual']=before['assignment']==after['assignment']
  excluded={'authority','alternativeSignature'}
  comparison[name]['occurrencesEqualIgnoringRepresentation']=[{k:v for k,v in row.items() if k not in excluded} for row in before['selectedOccurrences']]==[{k:v for k,v in row.items() if k not in excluded} for row in after['selectedOccurrences']]
 else:
  comparison[name]['bothExpectedRejections']=before['passed'] and after['passed'] and before['fedReturncode'] not in (None,0) and after['fedReturncode'] not in (None,0)
a.output.write_text(json.dumps({'baseline':b,'after':n,'comparison':comparison,'measurementCaveat':'One run per case; compile timings include JIT and external load. coordinatorMemory pool high-water sums are not simultaneous heap peaks. Docker stats are sampled whole-container usage.'},indent=2)+'\n')
print(json.dumps(comparison,indent=2))
