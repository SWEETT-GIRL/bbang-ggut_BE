@echo off
rem Approval server on :8091 with WRITER credentials (dataops_writer) - no sync worker. Stop it after approvals.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_writer_server.bat"   (outside the Claude app process tree)
rem ASCII only: cmd.exe reads .bat as CP949 on Korean Windows.
start "" /b powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0run_loop.ps1" -Port 8091 -NoWorker -Writer
exit
