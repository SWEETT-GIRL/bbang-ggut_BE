@echo off
rem Approve store.region proposals (Kakao reverse-geocoded, 법정동) via writer server :8091 - detached from the Claude app process tree.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_approve_region.bat"
cd /d "%~dp0.."
start "" /b python -u scripts\approve_stage3_pending.py --server http://localhost:8091 --families region >> "C:\Users\USER\sw17\dataops-logs\approve_region_20260908.log" 2>&1
exit
