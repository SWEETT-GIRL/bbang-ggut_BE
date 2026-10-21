@echo off
rem Re-judge all stores from stored snapshots (deletes PENDING, regenerates incl. menu.<id>.description) on the writer server :8091 - detached from the Claude app process tree.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_reconcile_all.bat"
start "" /b curl -s -m 36000 -X POST http://localhost:8091/api/snapshots/reconcile-all -o "C:\Users\USER\sw17\dataops-logs\reconcile_all_20260907.json"
exit
