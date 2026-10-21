@echo off
rem Stage 2: reject junk then approve menu.add proposals via writer server :8091, detached from the Claude app process tree.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_approve_menu_add.bat"
cd /d "%~dp0.."
start "" /b python -u scripts\approve_menu_add_pending.py --server http://localhost:8091 >> "C:\Users\USER\sw17\dataops-logs\approve_menu_add_20260906.log" 2>&1
exit
