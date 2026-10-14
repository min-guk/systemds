#!/usr/bin/env bash
set -u
run_id="$1"
out="$2"
printf 'timestamp\tcontainer\tcontainer_cpu\tcontainer_mem_usage\tcontainer_mem_percent\tcoordinator_host_pid\tcoordinator_cpu\tcoordinator_rss_kib\tcoordinator_vsz_kib\tcoordinator_elapsed_s\tcoordinator_vmhwm_kib\n' > "$out"
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
  timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  stats=$(docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}' "$container" 2>/dev/null || true)
  IFS='|' read -r container_cpu container_mem container_mem_percent <<< "$stats"
  row=$(docker top "$container" -eo pid,pcpu,rss,vsz,etimes,args 2>/dev/null | awk '/org\.apache\.sysds\.api\.DMLScript/ && /\/cases\/.*\/fed\.dml/ {print $1"|"$2"|"$3"|"$4"|"$5; exit}')
  pid=''; cpu=''; rss=''; vsz=''; elapsed=''; vmhwm=''
  if [[ -n "$row" ]]; then
    IFS='|' read -r pid cpu rss vsz elapsed <<< "$row"
    vmhwm=$(awk '/^VmHWM:/ {print $2}' "/proc/$pid/status" 2>/dev/null || true)
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$timestamp" "$container" "$container_cpu" "$container_mem" "$container_mem_percent" "$pid" "$cpu" "$rss" "$vsz" "$elapsed" "$vmhwm" >> "$out"
  sleep 1
done
exit 2
