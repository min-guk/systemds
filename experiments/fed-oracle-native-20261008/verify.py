#!/usr/bin/env python3
"""Compile the current FedPlanner changes over the pinned pre-change engine; save evidence."""
import argparse,hashlib,json,pathlib,subprocess
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--build-root',type=pathlib.Path,
 default=pathlib.Path('/grid/3/cofee-lm-sweep-mchoi-20260914/fed-oracle-native-20261008/engine-integration-main-50855b5'),
 help='Prepared engine overlay directory; use a separate directory for each parallel lane')
mode=parser.add_mutually_exclusive_group()
mode.add_argument('--compile-only',action='store_true')
mode.add_argument('--test',nargs='+',metavar='CLASS')
args=parser.parse_args()
repo=pathlib.Path(__file__).resolve().parents[2]
art=pathlib.Path(__file__).resolve().parent
baseline=repo/'experiments/general-factorized-plan-space-20261008'
root=args.build_root.resolve()
deps='/home/mchoi/w1357-stage-main276-20261008T1025Z/systemds/target/lib/*'
main_extra=['placement/CpRuleFamily.java','placement/PlacementSupportRelations.java','placement/PlannerCandidateSpaceAudit.java','fedCostBased/fedExact/ExactCategoricalSolver.java','fedCostBased/fedExact/ExactPhysicalReducedSolver.java']
test_extra=['placement/PlacementSupportDeletionWorklistTest.java','placement/CpRuleFamilyTest.java','fedCostBased/fedExact/ExactFiniteSupportInputFactorTest.java','fedCostBased/fedExact/ExactRealizationSupportSparseRowsTest.java','fedCostBased/fedExact/ExactNativeLocalSourceProjectionTest.java','fedCostBased/fedExact/ExactPhysicalReducedSolverTest.java']
cp=f'{root}/classes:{root}/test-classes:{repo}/src/test/resources:{deps}'
def run(name,cmd):
 (art/(name+'-command.json')).write_text(json.dumps(cmd,indent=2)+'\n')
 with (art/(name+'.log')).open('w') as log:
  result=subprocess.run(cmd,cwd=repo,stdout=log,stderr=subprocess.STDOUT)
 print(name,result.returncode,flush=True)
 if result.returncode:
  print((art/(name+'.log')).read_text()[-7000:]);raise SystemExit(result.returncode)
for label,extra,target in [('main',main_extra,'classes'),('tests',test_extra,'test-classes')]:
 previous=json.loads((baseline/(label+'-compile-command.json')).read_text())
 relative=[p[p.index('src/'): ] for p in previous if p.endswith('.java')]
 prefix='src/main/java/org/apache/sysds/hops/fedplanner/' if label=='main' else 'src/test/java/org/apache/sysds/hops/fedplanner/'
 relative+= [prefix+p for p in extra if (repo/(prefix+p)).exists()]
 if label=='main':
  relative.append('src/main/java/org/apache/sysds/parser/DMLTranslator.java')
 for git_cmd in [['git','diff','--name-only','e468797556e6789100736355ca2463941302221f','HEAD','--',('src/main/java' if label=='main' else 'src/test/java')+'/org/apache/sysds/hops/fedplanner'],['git','diff','--name-only','HEAD','--',('src/main/java' if label=='main' else 'src/test/java')+'/org/apache/sysds/hops/fedplanner'],['git','ls-files','--others','--exclude-standard','--',('src/main/java' if label=='main' else 'src/test/java')+'/org/apache/sysds/hops/fedplanner']]:
  relative += [p for p in subprocess.check_output(git_cmd,cwd=repo,text=True).splitlines() if p.endswith('.java')]
 if label=='tests':
  relative+= [str(p.relative_to(repo)) for p in (repo/'src/test/java/org/apache/sysds/hops/fedplanner').rglob('*CpRuleFamily*Test.java')]
 relative=list(dict.fromkeys(relative)); hashes={}
 for rel in relative:
  data=(repo/rel).read_bytes(); dest=root/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data);hashes[rel]=hashlib.sha256(data).hexdigest()
 (art/(label+'-sha256.json')).write_text(json.dumps(hashes,indent=2)+'\n')
 run(label+'-compile',['javac','-J-Xmx3g','-Xlint:unchecked','-cp',cp,'-d',str(root/target),*[str(root/p) for p in relative]])
 if label=='tests':
  compiled_tests=[p.removeprefix('src/test/java/')[:-5].replace('/','.') for p in relative if p.endswith('Test.java')]
if args.compile_only:
 raise SystemExit(0)
cmd=json.loads((baseline/'tests-command.json').read_text()); cmd[cmd.index('-cp')+1]=cp
for rel in test_extra:
 if (repo/('src/test/java/org/apache/sysds/hops/fedplanner/'+rel)).exists():
  name='org.apache.sysds.hops.fedplanner.'+rel[:-5].replace('/','.')
  if name not in cmd:cmd.append(name)
for p in (repo/'src/test/java/org/apache/sysds/hops/fedplanner').rglob('*CpRuleFamily*Test.java'):
 name=str(p.relative_to(repo/'src/test/java'))[:-5].replace('/','.')
 if name not in cmd:cmd.append(name)
for name in compiled_tests:
 if name not in cmd:cmd.append(name)
if args.test:
 cmd=cmd[:cmd.index('org.junit.runner.JUnitCore')+1]+args.test
run('tests',cmd)
print((art/'tests.log').read_text()[-500:])
