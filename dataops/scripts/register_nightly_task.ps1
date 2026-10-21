# 매일 새벽 최신화 작업을 윈도우 작업 스케줄러에 등록한다 (한 번만 실행). docs/weekly-refresh.md
#   powershell -ExecutionPolicy Bypass -File scripts\register_nightly_task.ps1            # 매일 03:00
#   powershell -ExecutionPolicy Bypass -File scripts\register_nightly_task.ps1 -At 02:30
# 로그인한 사용자 세션에서 돈다 — Docker Desktop 이 그 세션에서 떠 있어야 하기 때문이다. 절전에서 깨어나 실행하고(WakeToRun),
# 놓친 실행은 켜지는 대로 한다(StartWhenAvailable). 전원 옵션의 "절전 모드 해제 타이머 허용"을 켜 둘 것.
param(
  [string]$RepoDir = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)),
  [string]$At = "03:00",
  [string]$TaskName = "bbanggut-dataops-nightly",
  [string]$Python = "python"
)
$script = Join-Path $RepoDir "scripts\run_nightly.ps1"
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
  -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`" -Python `"$Python`"" `
  -WorkingDirectory $RepoDir
$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet -WakeToRun -StartWhenAvailable -MultipleInstances IgnoreNew `
  -ExecutionTimeLimit (New-TimeSpan -Hours 12) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -RunLevel Highest -Force `
  -Description "bbang-ggut dataops: daily Naver crawl (due stores), Sunday auto-apply + images + report" | Out-Null
Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName, State
Write-Output "registered: $TaskName daily at $At -> $script"
