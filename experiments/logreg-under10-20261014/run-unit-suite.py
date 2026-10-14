from pathlib import Path
import subprocess
root=Path(__file__).resolve().parent
cmd=(root/"final-unit-command.txt").read_text().splitlines()
with (root/"final-unit.log").open("w") as output:
    result=subprocess.run(cmd,stdout=output,stderr=subprocess.STDOUT)
print("JUnit exit:",result.returncode)
raise SystemExit(result.returncode)
