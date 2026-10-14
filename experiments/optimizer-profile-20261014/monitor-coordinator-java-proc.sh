#!/usr/bin/env bash
set -u
run_id="$1"
out="$2"
printf 'epoch_ms\ttimestamp\tcase\tcontainer\thost_pid\tutime_ticks\tstime_ticks\tproc_rss_pages\tvmrss_kib\tvmhwm_kib\n' > "$out"
seen=0
deadline=$((SECONDS+1900))
while (( SECONDS < deadline )); do
  container=''
  while IFS= read -r candidate; do
    [[ -z "$candidate" ]] && continue
    mounts=$(docker inspect --format '{{range .Mounts}}{{println .Source}}{{end}}' "$candidate" 2>/dev/null || true)
    if [[ "$mounts" == *"/$run_id"* ]]; then container="$candidate"; break; fi
  done < <(docker ps --format '{{.Names}}')
  if [[ -z "$container" ]]; then
    if (( seen )); then exit 0; fi
    sleep 1
    continue
  fi
  seen=1
  row=$(docker top "$container" -eo pid,args 2>/dev/null | awk '$2=="java" && /org\.apache\.sysds\.api\.DMLScript/ && /\/cases\/.*\/fed\.dml/ {c=($0 ~ /\/ml_logreg\/fed\.dml/ ? "ml_logreg" : ($0 ~ /\/ml_glm\/fed\.dml/ ? "ml_glm" : "unknown")); print $1"|"c; exit}')
  pid=''; case_name=''
  if [[ -n "$row" ]]; then
    IFS='|' read -r pid case_name <<< "$row"
    if [[ -r "/proc/$pid/stat" ]]; then
      read -r utime stime rsspages < <(awk '{print $14,$15,$24}' "/proc/$pid/stat")
      read -r vmrss vmhwm < <(awk '/^VmRSS:/ {rss=$2} /^VmHWM:/ {hwm=$2} END {print rss,hwm}' "/proc/$pid/status")
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%s%3N)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$case_name" "$container" "$pid" "$utime" "$stime" "$rsspages" "$vmrss" "$vmhwm" >> "$out"
    fi
  fi
  sleep 1
done
exit 2
