# 최신화 루프 기동 — dataops 서버(:8090, 이 레포 폴더) + sync_worker. 로그는 C:\Users\USER\sw17\dataops-logs\.
# 운영 자격은 -EnvFile(기본: localdata 워크트리 .env)에서 PROD_DB_* 만 읽어 자식 프로세스 환경변수로 넘긴다 (파일에 쓰지 않음).
# 반드시 run_loop.bat 를 explorer.exe 로 실행할 것 — Claude 앱(MSIX) 프로세스 트리 안에서 띄우면 패키지 ID 를 물려받아
# 앱 업데이트를 막고(“파일을 다른 응용 프로그램에서 사용 중”), 앱이 죽으면 같이 죽는다 (2026-09-04 실측).
param(
  [string]$RepoDir = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)),
  [string]$EnvFile = "C:\Users\USER\sw17\bbang-ggut_dataops_BE-localdata\.env",
  [int]$Port = 8090,
  [switch]$NoWorker,
  [switch]$Writer   # 승인(approve → ServingWriter)용: 운영 자격을 dataops_writer + DATAOPS_WRITER_PASSWORD 로 바꿔 띄운다. 승인 끝나면 내릴 것.
)
$LogDir = "C:\Users\USER\sw17\dataops-logs"
New-Item -ItemType Directory -Force $LogDir | Out-Null
$writerPw = $null
foreach ($line in Get-Content $EnvFile) {
  if ($line -match '^(PROD_DB_URL|PROD_DB_USERNAME|PROD_DB_PASSWORD)=(.*)$') { Set-Item -Path "env:$($matches[1])" -Value $matches[2].Trim() }
  if ($line -match '^DATAOPS_WRITER_PASSWORD=(.*)$') { $writerPw = $matches[1].Trim() }
}
if ($Writer) {
  if (-not $writerPw) { "$(Get-Date -Format 'HH:mm:ss') writer password missing in env file" | Tee-Object -FilePath "$LogDir\run_loop.log" -Append; exit 1 }
  $env:PROD_DB_USERNAME = "dataops_writer"
  $env:PROD_DB_PASSWORD = $writerPw
  "$(Get-Date -Format 'HH:mm:ss') writer mode on :$Port (dataops_writer)" | Tee-Object -FilePath "$LogDir\run_loop.log" -Append
}
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$up = $false
try { $up = ((Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 3 -ErrorAction Stop).status -eq "UP") } catch {}
if (-not $up) {
  Start-Process -FilePath "$RepoDir\gradlew.bat" -ArgumentList "bootRun","--no-daemon","-q","--args=--server.port=$Port" -WorkingDirectory $RepoDir `
    -RedirectStandardOutput "$LogDir\server-$stamp.out.log" -RedirectStandardError "$LogDir\server-$stamp.err.log" -WindowStyle Hidden | Out-Null
  for ($i = 0; $i -lt 42 -and -not $up; $i++) {
    Start-Sleep -Seconds 10
    try { $up = ((Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 3 -ErrorAction Stop).status -eq "UP") } catch {}
  }
}
"$(Get-Date -Format 'HH:mm:ss') server :$Port up=$up" | Tee-Object -FilePath "$LogDir\run_loop.log" -Append
if ($up -and -not $NoWorker) {
  $running = Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { $_.CommandLine -like "*sync_worker.py*" }
  if (-not $running) {
    Start-Process -FilePath "python" -ArgumentList "-u","scripts\sync_worker.py","--server","http://localhost:$Port" -WorkingDirectory $RepoDir `
      -RedirectStandardOutput "$LogDir\sync_worker-$stamp.out.log" -RedirectStandardError "$LogDir\sync_worker-$stamp.err.log" -WindowStyle Hidden | Out-Null
    "$(Get-Date -Format 'HH:mm:ss') worker started" | Tee-Object -FilePath "$LogDir\run_loop.log" -Append
  } else {
    "$(Get-Date -Format 'HH:mm:ss') worker already running (pid $($running.ProcessId))" | Tee-Object -FilePath "$LogDir\run_loop.log" -Append
  }
}
