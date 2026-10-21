@echo off
rem Photo backfill for all target stores (owner-registered Naver photos -> app from-url API). Detached, resumable via --skip.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_image_backfill.bat"
cd /d "%~dp0.."
start "" /b python -u scripts\image_backfill_worker.py --all >> "C:\Users\USER\sw17\dataops-logs\image_backfill_20260909.log" 2>&1
exit
