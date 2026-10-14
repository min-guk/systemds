# Pre-existing eager shape convergence issue

A regression fixture attempted during normal-return repair:

```dml
f=function(boolean q,matrix[double] X) return(matrix[double] Y){
  if(q){Y=abs(X);}else{stop('invalid input');}
}
A=matrix(1,rows=8,cols=4);R=matrix(0,rows=8,cols=4);
p=as.scalar(rand(rows=1,cols=1))>0.5;
while(p){R=f(p,A);B=t(R);p=as.scalar(rand(rows=1,cols=1))>0.5;}
C=t(R);print(sum(C));
```

New implementation probe interrupted after stack capture (regression-v2-stack.txt);
then the exact same compiled test fixture was run with pre-change MemoShapes.java
in baseline-classes ahead of target/classes. It also timed out after 25s:
baseline-loop-regression.log, exit124. Both are eager MemoShapes.drain before
contextual returnedShape evaluation, not Docker/runtime results.

This is not resolved or silently ignored. The focused phase/cardinality test now
uses a loop inside the conditional-return function, avoiding a new callback-return
eager SCC while still checking FIRST/MIDDLE/LAST and ZERO/ONE/MANY return shapes.
The callback-in-loop convergence issue remains a separately tracked known gap;
the replacement test does not certify that program. No fixed round cap was added.
