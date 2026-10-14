#!/usr/bin/env bash
set -u
run_id="$1"
out="$2"
printf 'epoch_ms\ttimestamp\tcontainer\thost_pid\tutime_ticks\tstime_ticks\tproc_rss_pages\tvmrss_kib\tvmhwm_kib\n' > "$out"
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
  pid=$(docker top "$container" -eo pid,args 2>/dev/null | awk '/org\.apache\.sysds\.api\.DMLScript/ && /\/cases\/.*\/fed\.dml/ {print $1; exit}')
  if [[ -n "$pid" && -r "/proc/$pid/stat" ]]; then
    read -r utime stime rsspages < <(awk '{print $14,$15,$24}' "/proc/$pid/stat")
    read -r vmrss vmhwm < <(awk '/^VmRSS:/ {rss=$2} /^VmHWM:/ {hwm=$2} END {print rss,hwm}' "/proc/$pid/status")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%s%3N)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$container" "$pid" "$utime" "$stime" "$rsspages" "$vmrss" "$vmhwm" >> "$out"
  fi
  sleep 1
done
exit 2
