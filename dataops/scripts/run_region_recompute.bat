@echo off
rem Region recompute (Kakao coord2regioncode, all ACTIVE stores) - detached from the Claude app process tree. Resumable.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_region_recompute.bat"
cd /d "%~dp0.."
start "" /b python -u scripts\region_recompute.py --fetch --report >> "C:\Users\USER\sw17\dataops-logs\region_recompute_20260908.log" 2>&1
exit
