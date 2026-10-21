@echo off
rem Region recompute, pass 2: fetch H (administrative dong) for unresolved/changed stores and re-report in H mode. Detached, resumable.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_region_refetch_h.bat"
cd /d "%~dp0.."
start "" /b python -u scripts\region_recompute.py --refetch-h --report --mode H >> "C:\Users\USER\sw17\dataops-logs\region_recompute_20260908.log" 2>&1
exit
