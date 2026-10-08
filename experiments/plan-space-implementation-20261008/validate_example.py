import json, sys
from pathlib import Path

def physical(anchor):
    if anchor is None: return None
    return (anchor['fType'], tuple(sorted((p['worker'].split('/')[0], tuple(p['begin']), tuple(p['end'])) for p in anchor['partitions'])))

def relations(d):
    rows={r['id']:r for r in d['realizations']}
    hops={h['id']:h for h in d['hops']}
    actions={a['id']:a for a in d['inputBindingRelocations']}
    def rowkey(r):
        return (r['rule'],r['owner'],r['state'],r['layout'],physical(r['anchor']),r['executionFType'],r['derivedOutputMaterialization'])
    result=set()
    for r in rows.values():
        for c in r['supports']:
            bindings=[]
            for b in c['bindings']:
                s=rows[b['source']]
                action=None; kind=b['kind']
                if kind=='RELOCATION':
                    a=actions[b['action']]; h=hops[s['owner']]
                    kind_type=a.get('materializationFType',a['target'].split('/')[2])
                    anchor=a['anchor']; parts=anchor['partitions']; dims=(h['rows'],h['cols'])
                    assert kind_type in ('ROW','COL','BROADCAST','FULL') and min(dims)>0
                    if kind_type in ('BROADCAST','FULL'):
                        target=(kind_type,tuple(sorted((p['worker'].split('/')[0], (0,0), dims) for p in parts)))
                    elif anchor['fType']==kind_type and tuple(max(p['end'][axis] for p in parts) for axis in (0,1))==dims:
                        target=physical(anchor)
                    else:
                        # The two fixtures use ROW/COL-preserving operations, not mixed-layout matrix multiply.
                        axis=0 if kind_type=='ROW' else 1
                        seed_axis=0 if anchor['fType']=='ROW' else 1
                        ordered=sorted(parts,key=lambda p:(p['begin'][seed_axis],p['end'][seed_axis]))
                        q,rem=divmod(dims[axis],len(parts)); ranges=[]
                        for i,p in enumerate(ordered):
                            begin=[0,0];end=list(dims);begin[axis]=i*q+min(i,rem);end[axis]=begin[axis]+q+(i<rem)
                            ranges.append((p['worker'].split('/')[0],tuple(begin),tuple(end)))
                        target=(kind_type,tuple(sorted(ranges)))
                    if physical(s['anchor'])==target: kind='DIRECT'
                    else: action=(a['sourceVariable'],a['target'],target,tuple(a['consumers']))
                bindings.append((b['input'],rowkey(s),kind,action))
            result.add((rowkey(r),tuple(bindings),physical(c['nativePool']),c['nativeLayoutExact']))
    return result

old=json.loads(Path(sys.argv[1]).read_text());new=json.loads(Path(sys.argv[2]).read_text())
a,b=relations(old),relations(new)
summary={'projection':'rule/state/output layout/source binding/actual transfer; excludes opaque proof signatures',
         'baselineRows':len(a),'optimizedRows':len(b),'onlyBaseline':len(a-b),'onlyOptimized':len(b-a),
         'sameRules':old['rules']==new['rules'],'equalProjectedRelations':a==b}
print(json.dumps(summary,indent=2))
if a!=b:
    Path(sys.argv[2]+'.relation-diff.txt').write_text('BASELINE ONLY\n'+str(sorted(map(str,a-b)))+'\nOPTIMIZED ONLY\n'+str(sorted(map(str,b-a))))
    sys.exit(1)
