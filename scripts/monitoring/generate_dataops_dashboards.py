"""Generate portable Grafana classic JSON. No deployment or service connections."""
import json
from pathlib import Path

OUTPUT = Path(__file__).resolve().parents[2] / "monitoring" / "grafana"
PROM = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
CLOUD = {"type": "cloudwatch", "uid": "${DS_CLOUDWATCH}"}


def base(uid, title, datasource, plugin):
    return {"id": None, "uid": uid, "title": title, "tags": ["bbanggut", "dataops", "v0"],
            "schemaVersion": 39, "version": 1, "editable": True, "timezone": "Asia/Seoul",
            "refresh": "30s", "time": {"from": "now-48h", "to": "now"},
            "__inputs": [{"name": datasource, "label": plugin, "type": "datasource", "pluginId": plugin,
                          "pluginName": plugin}],
            "annotations": {"list": [{"name": "Annotations & Alerts", "type": "dashboard", "builtIn": 1,
                                       "enable": True, "hide": True, "datasource": {"type": "grafana", "uid": "-- Grafana --"}}]},
            "templating": {"list": []}, "panels": [], "links": []}


def add(dashboard, panel):
    panel["id"] = len(dashboard["panels"]) + 1
    dashboard["panels"].append(panel)


def text(dashboard, title, content, y, height=4):
    add(dashboard, {"type": "text", "title": title, "gridPos": {"x": 0, "y": y, "w": 24, "h": height},
                    "options": {"mode": "markdown", "content": content}})


def chart(dashboard, title, expression, x, y, unit="short", description="", kind="timeseries", width=8):
    add(dashboard, {"type": kind, "title": title, "description": description, "datasource": PROM,
                    "gridPos": {"x": x, "y": y, "w": width, "h": 7},
                    "targets": [{"refId": "A", "expr": expression, "legendFormat": "{{instance}} {{pool}} {{workflow}} {{step}} {{outcome}}",
                                 "range": kind == "timeseries", "instant": kind == "stat", "datasource": PROM}],
                    "fieldConfig": {"defaults": {"unit": unit, "noValue": "수집 확인 필요",
                                                   "color": {"mode": "palette-classic"}}, "overrides": []},
                    "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull"]},
                                "tooltip": {"mode": "multi"}, "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False}}})


def variable(name, label, query):
    return {"name": name, "label": label, "type": "query", "datasource": PROM, "refresh": 1,
            "query": {"query": query, "refId": name}, "definition": query, "sort": 1,
            "includeAll": True, "allValue": ".*", "multi": True,
            "current": {"text": "All", "value": "$__all", "selected": True}, "options": []}


def spring_batch_dashboard():
    dashboard = base("bbang-dataops-v0", "빵긋 · DataOps 실행과 Spring 영향 · V0", "DS_PROMETHEUS", "prometheus")
    dashboard["templating"]["list"] = [
        variable("spring_job", "Spring scrape job", "label_values(jvm_memory_used_bytes, job)"),
        variable("spring_instance", "Spring 인스턴스", 'label_values(jvm_memory_used_bytes{job=~"$spring_job"}, instance)'),
        {"name": "workflow", "label": "DataOps 실행", "type": "custom", "query": "nightly,collect-only,apply,apply-dry-run,preflight,init,review-only",
         "current": {"text": "nightly", "value": "nightly"}, "includeAll": False, "multi": False,
         "options": [{"text": value, "value": value, "selected": value == "nightly"}
                     for value in ("nightly", "collect-only", "apply", "apply-dry-run", "preflight", "init", "review-only")]},
    ]
    dashboard["links"] = [{"title": "RDS CloudWatch — 같은 시간 범위", "url": "/d/bbang-dataops-rds", "type": "link", "includeVars": False, "keepTime": True}]
    text(dashboard, "먼저 읽기 · 지표가 없으면 정상 0이 아닙니다",
         "API Spring의 기존 `/actuator/prometheus` 지표와 **weekly_refresh** 실행 지표를 같은 시간축으로 비교합니다. "
         "Spring scrape job에서 **API 대상**을 선택하세요. 다른 EC2라도 같은 RDS의 I/O·락·연결을 공유할 수 있습니다.\n\n"
         "배치 지표는 이 코드 배포 + `DATAOPS_PUSHGATEWAY_URL` + Prometheus scrape 설정 이후 나타납니다. "
         "**WAS의 단발성 `--batch.job` 작업은 이번 배치 지표에 포함되지 않습니다.** "
         "`RUNNING`은 살아 있다는 증명이 아니라 종료 기록 미수신일 수 있습니다. "
         "실행 ID는 로그/JSON에서 찾고 메트릭 라벨에는 넣지 않습니다.", 0, 5)
    scope = '{job=~"${spring_job:regex}",instance=~"${spring_instance:regex}"}'
    http_scope = scope[:-1] + ',uri!~"/actuator.*"}'
    batch = '{job="dataops-workflow",workflow="$workflow"}'
    chart(dashboard, "마지막 종료 코드 · 0 완료 / 3 차단 / 1·2 실패", "bbang_dataops_workflow_last_exit_code" + batch, 0, 5, kind="stat",
          description="-1은 종료 이력 없음. 130/143은 중단. 종료 0은 프로세스 완료이며 수집 전량 성공·운영 반영 완료와 다릅니다.")
    chart(dashboard, "현재 실행 또는 종료 미확정 · 1 / 비실행 · 0", "bbang_dataops_workflow_running" + batch, 8, 5, kind="stat",
          description="SIGKILL/EC2 중단/최종 push 실패 시 1이 남을 수 있습니다. 실행 나이와 SSM 로그를 함께 확인하세요.")
    chart(dashboard, "마지막 성공 후 경과", '(time() - bbang_dataops_workflow_last_success_timestamp_seconds' + batch + ') and (bbang_dataops_workflow_last_success_timestamp_seconds' + batch + ' > 0)', 16, 5, unit="s", kind="stat",
          description="성공 이력이 없으면 값이 없습니다. nightly/시험 반영/실제 반영은 다른 workflow입니다.")
    chart(dashboard, "배치 최근 실행 소요 시간", "bbang_dataops_workflow_last_duration_seconds" + batch, 0, 12, unit="s",
          description="각 시점 마지막 완료 실행의 소요 시간(gauge). rate/increase를 적용하지 않습니다.")
    chart(dashboard, "현재 실행 / 미확정 상태의 경과 시간", '(time() - bbang_dataops_workflow_last_start_timestamp_seconds' + batch + ') and (bbang_dataops_workflow_running' + batch + ' == 1)', 8, 12, unit="s")
    chart(dashboard, "배치 메트릭 마지막 수신 후 경과", 'time() - push_time_seconds' + batch, 16, 12, unit="s",
          description="실행 시작·종료에 push합니다. 실행 중 매초 heartbeat가 아니며 스케줄이 꺼져 있으면 계속 증가합니다.")
    chart(dashboard, "최근 완료 실행 · 단계별 시간", "bbang_dataops_step_last_duration_seconds" + batch, 0, 19, unit="s", width=12)
    chart(dashboard, "보존된 실행 이력 · 상태별 건수", 'bbang_dataops_workflow_observed_runs{job="dataops-workflow",workflow="$workflow"}', 12, 19, width=12,
          description="로컬 JSON을 보존한 기간의 gauge입니다. 현재 Grafana 조회 기간의 건수나 Prometheus counter가 아닙니다. RUNNING은 현재 또는 미확정 실행입니다.")
    text(dashboard, "Spring API 영향 · 함께 증가했는지 확인하고 DB 원인으로 단정하지 않습니다",
         "배치 실행 구간에 API p95·5xx·Hikari pending이 증가했는지 확인합니다. "
         "Hikari pending은 해당 JVM의 풀 대기이며 RDS 전체 연결 수나 DB 락 수가 아닙니다. "
         "원인 확인은 RDS 대시보드와 Database Insights / pg_stat_activity·pg_locks를 함께 사용하세요.", 26, 3)
    chart(dashboard, "API 요청 수 / 초", 'sum by (instance) (rate(http_server_requests_seconds_count' + http_scope + '[$__rate_interval]))', 0, 29, unit="reqps")
    chart(dashboard, "API 응답 p95", 'histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket' + http_scope + '[$__rate_interval])))', 8, 29, unit="s",
          description="http.server.requests 히스토그램이 켜져 있어야 합니다. 코드의 API 기본 설정은 활성입니다. 트래픽이 없으면 값이 없을 수 있습니다.")
    chart(dashboard, "API 5xx 비율", 'sum by (instance) (rate(http_server_requests_seconds_count' + http_scope[:-1] + ',status=~"5.."}[$__rate_interval])) / sum by (instance) (rate(http_server_requests_seconds_count' + http_scope + '[$__rate_interval]))', 16, 29, unit="percentunit",
          description="5xx 시계열이 아직 생성되지 않았으면 no data일 수 있습니다. 없는 수집값을 0으로 대체하지 않습니다.")
    chart(dashboard, "Hikari 커넥션 대기", "hikaricp_connections_pending" + scope, 0, 36)
    chart(dashboard, "Hikari 풀 사용률", "hikaricp_connections_active" + scope + " / hikaricp_connections_max" + scope, 8, 36, unit="percentunit")
    chart(dashboard, "Hikari 연결 획득 timeout / 초", "rate(hikaricp_connections_timeout_total" + scope + "[$__rate_interval])", 16, 36, unit="ops")
    chart(dashboard, "Spring 프로세스 CPU", "process_cpu_usage" + scope, 0, 43, unit="percentunit")
    chart(dashboard, "JVM heap 사용량", 'sum by (instance) (jvm_memory_used_bytes' + scope[:-1] + ',area="heap"})', 8, 43, unit="bytes")
    chart(dashboard, "GC pause 시간 / 초", "sum by (instance) (rate(jvm_gc_pause_seconds_sum" + scope + "[$__rate_interval]))", 16, 43, unit="s")
    chart(dashboard, "Spring scrape 상태", 'up{job=~"${spring_job:regex}",instance=~"${spring_instance:regex}"}', 0, 50, kind="stat", width=12)
    chart(dashboard, "Pushgateway scrape 상태", 'up{job="dataops-pushgateway"}', 12, 50, kind="stat", width=12,
          description="README의 scrape job 이름 기준. Pushgateway가 UP이어도 배치가 살아 있다는 뜻은 아닙니다.")
    text(dashboard, "다음 버전 · 아직 생성하지 않은 지표",
         "**V1 Spring Batch:** Job/Step 성공·실패, 재시작, read/write/skip/rollback. "
         "**V2 승인 반영:** outbox 대기 수·최고 대기 나이, 요청→반영 지연, 중복 차단·버전 충돌·미확정 대사. "
         "**V3 Kafka Consumer:** 파티션별 lag, 처리 시간, 재시도/DLT, 리밸런스. "
         "**V4 브로커:** ISR·미복제 파티션·리더 장애 복구.\n\n"
         "현재 이 기능들은 구현 전입니다. 임의의 메트릭 이름으로 정상 0 패널을 만들지 않았습니다. "
         "실험 시작·종료·배포 시각은 Grafana annotation으로 남기고 같은 입력/부하에서 비교하세요.", 57, 5)
    return dashboard


def rds_dashboard():
    dashboard = base("bbang-dataops-rds", "빵긋 · 공유 RDS 영향 · CloudWatch", "DS_CLOUDWATCH", "cloudwatch")
    dashboard["refresh"] = "1m"
    dashboard["templating"]["list"] = [
        {"name": "region", "label": "AWS Region", "type": "textbox", "query": "ap-northeast-2", "current": {"text": "ap-northeast-2", "value": "ap-northeast-2"}},
        {"name": "rds_instance", "label": "RDS instance identifier (DB 이름 아님)", "type": "textbox", "query": "YOUR_RDS_INSTANCE_IDENTIFIER", "current": {"text": "YOUR_RDS_INSTANCE_IDENTIFIER", "value": "YOUR_RDS_INSTANCE_IDENTIFIER"}},
    ]
    dashboard["links"] = [{"title": "배치 + Spring — 같은 시간 범위", "url": "/d/bbang-dataops-v0", "type": "link", "keepTime": True}]
    text(dashboard, "CloudWatch 데이터소스가 있을 때 import · 인스턴스 전체 지표",
         "상단에 실제 **RDS 인스턴스 식별자**를 입력하세요 (`soboro`/`dataops` 같은 database 이름이 아닙니다). "
         "같은 RDS 안의 database들이 공유하는 자원입니다. datasource의 AWS 읽기 권한이 필요합니다. "
         "일반 RDS CloudWatch 지표만 사용합니다. DB 락 대기·SQL별 부하는 별도 Database Insights나 PostgreSQL 관측이 필요합니다. "
         "지표 부재를 0으로 해석하지 마세요.", 0, 4)
    specifications = [
        ("RDS CPU", "CPUUtilization", "Average", "percent"),
        ("전체 DB 연결 수", "DatabaseConnections", "Maximum", "short"),
        ("가용 메모리", "FreeableMemory", "Minimum", "bytes"),
        ("Read latency", "ReadLatency", "Average", "s"),
        ("Write latency", "WriteLatency", "Average", "s"),
        ("Disk queue depth", "DiskQueueDepth", "Average", "short"),
        ("Read IOPS", "ReadIOPS", "Average", "iops"),
        ("Write IOPS", "WriteIOPS", "Average", "iops"),
        ("남은 스토리지", "FreeStorageSpace", "Minimum", "bytes"),
    ]
    for index, (title, metric, statistic, unit) in enumerate(specifications):
        add(dashboard, {"type": "timeseries", "title": title, "datasource": CLOUD,
                        "gridPos": {"x": index % 3 * 8, "y": 4 + index // 3 * 7, "w": 8, "h": 7},
                        "fieldConfig": {"defaults": {"unit": unit, "noValue": "수집 확인 필요"}, "overrides": []},
                        "targets": [{"refId": "A", "datasource": CLOUD, "queryMode": "Metrics", "metricQueryType": 0,
                                     "metricEditorMode": 0, "namespace": "AWS/RDS", "metricName": metric,
                                     "region": "$region", "dimensions": {"DBInstanceIdentifier": "$rds_instance"},
                                     "statistic": statistic, "period": "60", "matchExact": True}],
                        "options": {"tooltip": {"mode": "multi"}, "legend": {"displayMode": "list", "placement": "bottom"}}})
    return dashboard


if __name__ == "__main__":
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for filename, dashboard in (("dataops-spring-v0.json", spring_batch_dashboard()), ("dataops-rds-cloudwatch.json", rds_dashboard())):
        (OUTPUT / filename).write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
