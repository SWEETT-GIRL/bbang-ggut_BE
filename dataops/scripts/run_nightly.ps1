# 매일 새벽 최신화 — 작업 스케줄러가 부른다(register_nightly_task.ps1). 일요일이면 수집 뒤 자동 반영까지 한다.
# 순서: 로컬 DB 컨테이너 → 터널 확인 → 자격(.env, 일요일만 쓰기 계정) → 서버 기동 → weekly_refresh.py nightly → 서버 종료.
# 자격은 .env 에서 읽어 이 프로세스의 환경변수로만 넘긴다(파일에 쓰지 않음). 터널은 사람이 띄운 상시 서비스다 —
# 닫혀 있으면 weekly_refresh.py 가 건너뛰고 보고서에 남긴다. docs/weekly-refresh.md
param(
  [string]$RepoDir = (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)),
  [string]$EnvFile = "",
  [string]$Python = "python",
  [int]$Port = 8090,
  [string]$DbHost = "localhost",   # dataops DB 가 원격(오라클)이면 SSH 터널로 localhost:15434 에 붙여 두는 것을 권한다
  [int]$DbPort = 15434,
  [int]$ProdPort = 15432,
  [double]$CrawlHours = 6,
  [switch]$ForceApply
)
if (-not $EnvFile) { $EnvFile = Join-Path $RepoDir ".env" }
$LogDir = Join-Path $RepoDir "dataops-logs\weekly"
New-Item -ItemType Directory -Force $LogDir | Out-Null
$Log = Join-Path $LogDir "run_nightly.log"
function Write-Log([string]$m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Tee-Object -FilePath $Log -Append | Out-Null }

$apply = $ForceApply -or ((Get-Date).DayOfWeek -eq [DayOfWeek]::Sunday)
Write-Log "start (apply=$apply, crawlHours=$CrawlHours)"

# 1) dataops DB — 로컬 도커면 컨테이너를 깨운다. 원격(오라클)이면 컨테이너가 없어도 된다(터널·접속은 사전 점검이 본다)
if ($DbHost -eq "localhost" -or $DbHost -eq "127.0.0.1") {
  try { docker start bbanggut-dataops-db 2>$null | Out-Null } catch {}
}

# 2) 자격 — PROD_DB_URL/USERNAME/PASSWORD 는 읽기 계정, 반영하는 날만 dataops_writer 로 바꾼다
$writerPw = $null
foreach ($line in Get-Content $EnvFile) {
  if ($line -match '^(PROD_DB_URL|PROD_DB_USERNAME|PROD_DB_PASSWORD)=(.*)$') { Set-Item -Path "env:$($matches[1])" -Value $matches[2].Trim() }
  if ($line -match '^DATAOPS_WRITER_PASSWORD=(.*)$') { $writerPw = $matches[1].Trim() }
}
if ($apply) {
  if (-not $writerPw) { Write-Log "DATAOPS_WRITER_PASSWORD missing in $EnvFile - apply skipped, crawl only"; $apply = $false }
  else { $env:PROD_DB_USERNAME = "dataops_writer"; $env:PROD_DB_PASSWORD = $writerPw }
}
$env:DATAOPS_DB_HOST = "$DbHost"
$env:DATAOPS_DB_PORT = "$DbPort"
$env:PROD_DB_PORT = "$ProdPort"

# 3) 서버 — 이미 떠 있으면 자격을 알 수 없다. 반영하는 날엔 중단하고, 아니면 그대로 쓴다
$health = "http://localhost:$Port/actuator/health"
$up = $false
try { $up = ((Invoke-RestMethod -Uri $health -TimeoutSec 3 -ErrorAction Stop).status -eq "UP") } catch {}
$started = $false
if ($up -and $apply) {
  Write-Log "server already running on :$Port - cannot guarantee writer credentials, apply skipped"
  $apply = $false
}
if (-not $up) {
  $stamp = Get-Date -Format "yyyyMMdd-HHmm"
  Start-Process -FilePath (Join-Path $RepoDir "gradlew.bat") `
    -ArgumentList "bootRun", "--no-daemon", "-q", "--args=--server.port=$Port --spring.datasource.url=jdbc:postgresql://${DbHost}:$DbPort/dataops" `
    -WorkingDirectory $RepoDir -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $LogDir "server-$stamp.out.log") -RedirectStandardError (Join-Path $LogDir "server-$stamp.err.log") | Out-Null
  $started = $true
  for ($i = 0; $i -lt 42 -and -not $up; $i++) {
    Start-Sleep -Seconds 10
    try { $up = ((Invoke-RestMethod -Uri $health -TimeoutSec 3 -ErrorAction Stop).status -eq "UP") } catch {}
  }
}
Write-Log "server :$Port up=$up (startedHere=$started)"

# 4) 수집(+ 반영) — 사전 점검(터널·DB·서버)은 weekly_refresh.py 가 하고, 실패하면 보고서를 남기고 끝낸다
if ($up) {
  $pyArgs = @("-u", (Join-Path $RepoDir "scripts\weekly_refresh.py"), "nightly", "--server", "http://localhost:$Port",
              "--prod-port", "$ProdPort", "--crawl-hours", "$CrawlHours")
  if ($apply) { $pyArgs += "--apply" }
  & $Python @pyArgs *>> (Join-Path $LogDir "weekly_refresh.console.log")
  Write-Log "weekly_refresh exit=$LASTEXITCODE"
}

# 5) 여기서 띄운 서버는 내린다 — 쓰기 계정 서버를 켜 둔 채 두지 않는다
if ($started) {
  $owner = (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
  if ($owner) { Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue; Write-Log "server stopped (pid $owner)" }
}
Write-Log "end"
