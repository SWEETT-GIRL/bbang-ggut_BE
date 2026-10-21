@echo off
rem Stage 3: approve/reject change-type proposals by family rules via writer server :8091, detached from the Claude app process tree.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_approve_stage3.bat"
cd /d "%~dp0.."
start "" /b python -u scripts\approve_stage3_pending.py --server http://localhost:8091 >> "C:\Users\USER\sw17\dataops-logs\approve_stage3_20260907.log" 2>&1
exit
