#!/bin/bash
# 收集本机基本信息，输出为便于模型解析的分段文本。
set -u

section() { echo; echo "=== $1 ==="; }

section "host"
uname -a
if [ -f /etc/os-release ]; then cat /etc/os-release; fi
echo "hostname=$(hostname)"

section "cpu_load"
if command -v sysctl >/dev/null 2>&1 && sysctl -n hw.ncpu >/dev/null 2>&1; then
  echo "cores=$(sysctl -n hw.ncpu)"
else
  echo "cores=$(nproc 2>/dev/null || echo ?)"
fi
uptime

section "memory"
if command -v vm_stat >/dev/null 2>&1; then
  vm_stat
else
  free -h 2>/dev/null || cat /proc/meminfo | head -5
fi

section "disk"
df -h | head -20

section "top_processes"
ps -Ao pid,pcpu,pmem,comm | sort -k2 -nr | head -6
echo "--- by memory ---"
ps -Ao pid,pcpu,pmem,comm | sort -k3 -nr | head -6
