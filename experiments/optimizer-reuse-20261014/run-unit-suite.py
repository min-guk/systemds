from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parent
stage=sys.argv[1] if len(sys.argv)>1 else 'final'
prior=(root/'final-unit-command.txt').read_text().splitlines()
start=prior.index('org.junit.runner.JUnitCore')+1
classes=prior[start:]
cmd=prior[:start]+classes
(root/(stage+'-unit-command.txt')).write_text('\n'.join(cmd)+'\n')
(root/(stage+'-unit-classes.txt')).write_text('\n'.join(classes)+'\n')
with (root/(stage+'-unit.log')).open('w') as log:
    result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
print('JUnit classes:',len(classes),'exit:',result.returncode)
print('\n'.join((root/(stage+'-unit.log')).read_text().splitlines()[-45:]))
raise SystemExit(result.returncode)
