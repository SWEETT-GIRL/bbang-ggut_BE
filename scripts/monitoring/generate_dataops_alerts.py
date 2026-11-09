"""Generate paused Grafana-managed alerts; never connects to Grafana or Slack."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SELECTOR = '{job="dataops-workflow",workflow=~"freshness-(baseline|refresh|public|discovery)"}'
EXIT = "bbang_dataops_workflow_last_exit_code" + SELECTOR

# Boolean results keep healthy instances at 0, rather than silently dropping series.
RULES = (
    ("pushgateway", "DataOpsPushgatewayUnavailable", "5m",
     '(max(up{job="dataops-pushgateway"}) == bool 0) or vector(1)',
     "Pushgateway 수집 경로 이상", "Prometheus Targets, Pushgateway, 모니터링 서버 연결을 확인한다."),
    ("missing", "DataOpsDailyRefreshMissing", "15m",
     'sum(absent(bbang_dataops_workflow_last_success_timestamp_seconds{job="dataops-workflow",workflow="freshness-refresh"})) or vector(0)',
     "기존 가게 최신화 지표 미수신", "예약 실행, SSM 실행 결과와 Pushgateway 전송 로그를 확인한다."),
    ("stale", "DataOpsDailyRefreshStale", "15m",
     'time() - bbang_dataops_workflow_last_success_timestamp_seconds{job="dataops-workflow",workflow="freshness-refresh"} > bool 36 * 3600',
     "기존 가게 최신화가 36시간 동안 성공하지 않음", "스케줄 활성 여부와 실패·미완료 회차를 확인한다. 최초 성공 전 값 0도 포함한다."),
    ("unresolved", "DataOpsWorkflowUnresolved", "15m",
     '(time() - bbang_dataops_workflow_last_start_timestamp_seconds' + SELECTOR + ' > bool 8 * 3600)'
     ' * on(job,workflow) bbang_dataops_workflow_running' + SELECTOR,
     "8시간 동안 배치 종료 기록 미수신", "SSM 실행, EC2 상태와 JSON 이력을 대사한다. running=1만으로 프로세스 생존을 판단하지 않는다."),
    ("failed", "DataOpsWorkflowFailed", "5m",
     f'({EXIT} > bool 0) * ({EXIT} != bool 3) * ({EXIT} != bool 4)',
     "DataOps 최근 실행 실패 또는 중단", "종료 코드와 SSM 로그를 확인한다. 동일 회차 재개 여부를 판단하고 중복 실행을 피한다."),
    ("blocked", "DataOpsWorkflowBlocked", "30m", f'{EXIT} == bool 3',
     "DataOps 차단 상태가 30분 이상 지속", "API 쿼터·접근 차단을 확인한다. 원인 해소 전 반복 호출하지 않는다."),
)


def provision(datasource_uid, contact_point):
    rules = []
    for suffix, title, pending, expression, summary, description in RULES:
        rules.append({
            "uid": "bbang-dataops-" + suffix, "title": title, "condition": "B",
            "data": [
                {"refId": "A", "relativeTimeRange": {"from": 600, "to": 0},
                 "datasourceUid": datasource_uid,
                 "model": {"refId": "A", "expr": expression, "instant": True, "range": False,
                           "intervalMs": 1000, "maxDataPoints": 43200}},
                {"refId": "B", "relativeTimeRange": {"from": 0, "to": 0},
                 "datasourceUid": "__expr__",
                 "model": {"refId": "B", "type": "threshold", "expression": "A",
                           "conditions": [{"evaluator": {"type": "gt", "params": [0]},
                                           "operator": {"type": "and"},
                                           "reducer": {"type": "last", "params": []},
                                           "query": {"params": ["B"]}, "type": "query"}]}},
            ],
            "noDataState": "OK", "execErrState": "Alerting", "for": pending,
            "annotations": {"summary": summary, "description": description,
                            "dashboard_url": "https://grafana.bbangmapbbang.shop/d/bbang-dataops-v0",
                            "recovery_note": "Resolved는 해당 경보 조건 해제다. 업무 완료는 최근 성공 시각·종료 코드·실행 이력으로 확인한다."},
            "labels": {"service": "dataops", "environment": "prod", "severity": "warning"},
            "isPaused": True,
            # Direct routing preserves the existing notification policy tree.
            "notification_settings": {"receiver": contact_point,
                                      "group_by": ["grafana_folder", "alertname", "workflow"],
                                      "group_wait": "30s", "group_interval": "5m", "repeat_interval": "4h"},
        })
    return {"apiVersion": 1, "groups": [{"orgId": 1, "name": "dataops-operations",
                                          "folder": "DataOps", "interval": "1m", "rules": rules}]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--datasource-uid", default="dfutc56whwh6of", help="Existing production Prometheus UID")
    parser.add_argument("--contact-point", default="dataops-slack", help="Existing Grafana Slack contact point name")
    parser.add_argument("--output", type=Path, default=ROOT / "monitoring/grafana/alerting/dataops-rules.json")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(provision(args.datasource_uid, args.contact_point), ensure_ascii=False, indent=2) + "\n")
