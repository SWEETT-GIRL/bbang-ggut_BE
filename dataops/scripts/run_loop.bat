@echo off
rem Launch the dataops sync loop (server :8090 + sync_worker) OUTSIDE the Claude app process tree.
rem Run via:  explorer.exe "C:\Users\USER\sw17\bbang-ggut_dataops_BE\scripts\run_loop.bat"   (or double-click)
rem ASCII only: cmd.exe reads .bat as CP949 on Korean Windows, UTF-8 comments become garbage commands.
start "" /b powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%~dp0run_loop.ps1"
exit
