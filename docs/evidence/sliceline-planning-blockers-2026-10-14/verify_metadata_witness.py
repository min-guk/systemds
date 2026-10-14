#!/usr/bin/env python3
"""Logical witness for slicefinder_core lines105-120; NOT a workload/performance benchmark.
Uses exact Python integer arithmetic for the published expressions only.
"""
import collections
import hashlib
import json
import math
from pathlib import Path

def digest(value):
    return hashlib.sha256(json.dumps(value,separators=(',',':')).encode()).hexdigest()

def column_stats(rows):
    return {'rows':len(rows),'cols':len(rows[0]),'nnz':sum(v!=0 for r in rows for v in r),
            'columns':[{'min':min(col),'max':max(col),'sum':sum(col),
                        'distinct':sorted(set(col)),'histogram':sorted(collections.Counter(col).items())}
                       for col in zip(*rows)]}

def main():
    # Three equal 32-row worker ranges; preserve per-worker univariate statistics too.
    X=[r[:] for _ in range(3*8) for r in [[1,1],[1,1],[2,2],[2,2]]]
    ea=[v for _ in range(3*8) for v in [1,1,0,0]]
    eb=[v for _ in range(3*8) for v in [1,0,1,0]]
    min_sup=max(math.ceil(.01*len(X)),8)
    n2=4
    onehot=[[int(row[0]==1),int(row[0]==2),int(row[1]==1),int(row[1]==2)] for row in X]
    counts=[sum(row[j] for row in onehot) for j in range(n2)]
    statistics=[]; results=[]
    for e in [ea,eb]:
        statistics.append({'X':column_stats(X),'e':column_stats([[v] for v in e]),
                           'workers':[{'range':[i,i+32], 'X':column_stats(X[i:i+32]),
                                       'e':column_stats([[v] for v in e[i:i+32]])}
                                      for i in range(0,96,32)]})
        err=[sum(v*row[j] for v,row in zip(e,onehot)) for j in range(n2)]
        selected=[c>=min_sup and s>0 for c,s in zip(counts,err)]
        results.append({'eContentSha256':digest(e),'cCnts':counts,'err':err,
                        'selCols':selected,'attrRows':sum(selected),'S_shape':[sum(selected),n2]})
    assert statistics[0]==statistics[1]
    assert results[0]['S_shape']==[2,4] and results[1]['S_shape']==[4,4]
    assert results[0]['eContentSha256']!=results[1]['eContentSha256']
    out={'purpose':'information insufficiency witness, not actual data or performance evidence',
         'formulaSource':'slicefinder_core.dml:105-120', 'workers':3,'N':len(X),'minSup':min_sup,
         'sameStatisticalMetadata':statistics[0], 'sameXContentSha256':digest(X),
         'differentContentIdentity':True,'cases':results,
         'limitation':'No SystemDS/Docker workload execution; no runtime/optimum claim. Content hashes differ; identical statistics do not imply identical datasets.'}
    target=Path(__file__).with_name('metadata-witness.json')
    target.write_text(json.dumps(out,indent=2)+'\n')
    print('PASS: identical global/per-worker statistics; S shapes 2x4 versus 4x4; distinct e content identities')
if __name__=='__main__':
    main()
