@echo off
rem Resume fill-only approvals against the writer server :8091, detached from the Claude app process tree (10-min tool timeout would kill a child).
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_approve_fill.bat"
cd /d "%~dp0.."
start "" /b python -u scripts\approve_fill_pending.py --server http://localhost:8091 >> "C:\Users\USER\sw17\dataops-logs\approve_fill_20260906.log" 2>&1
exit
