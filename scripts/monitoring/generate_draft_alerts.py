"""메모 초안 버퍼 Grafana 관리형 알림(생성 시 일시정지 상태). Grafana·Slack 에 접속하지 않는다.

dataops 알림(generate_dataops_alerts.py)과 같은 틀 — 조건식은 bool 로 두어 정상이면 0 이 남고 시계열이 사라지지 않는다.
"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DRAFT_PUT = 'uri="/courses/{courseId}/stores/{storeId}/memo-draft",method="PUT"'
PUT_RATE = "rate(http_server_requests_seconds_count{" + DRAFT_PUT + "}[5m])"
PUT_503_RATE = "rate(http_server_requests_seconds_count{" + DRAFT_PUT + ',status="503"}[5m])'

# (suffix, title, for, expr, summary, description)
RULES = (
    ("backlog", "CourseDraftBacklogStuck", "2m",
     "max(draft_backlog) > bool 10",
     "초안 반영 대기 코스가 10개 넘게 2분 지속",
     "플러셔가 멈췄거나 DB 반영이 계속 실패하는 것. 앱 로그 `[메모 초안] DB 반영 실패`·`임대가 만료된 코스` 와 Hikari pending 을 본다. "
     "설계상 정상이면 backlog 는 2~10초 안에 0으로 돌아온다."),
    ("redis", "CourseDraftRedisUnreadable", "2m",
     "(min(draft_backlog) < bool 0) or vector(0)",
     "초안 Redis 를 읽지 못함 (draft_backlog = -1)",
     "Redis EC2 의 redis-draft 컨테이너(6380)·보안그룹·비밀번호를 본다. 그동안 초안 PUT 은 503 이고 앱은 PATCH 로 물러난다 — 유실은 아니다."),
    ("write503", "CourseDraftWrite503Ratio", "5m",
     "(sum(" + PUT_503_RATE + ") / sum(" + PUT_RATE + ") > bool 0.05) * (sum(" + PUT_RATE + ") > bool 0.1)",
     "초안 PUT 의 5% 넘게 503, 5분 지속 (요청이 초당 0.1 이상일 때만)",
     "Redis command timeout(1초)·연결 끊김·권한 판정 DB 실패. 요청이 거의 없을 때 한두 건으로 울리지 않게 최소 트래픽 조건을 둔다."),
    ("sessions", "CourseDraftSocketSessionsHigh", "5m",
     "max(draft_socket_sessions) > bool 2000",
     "한 인스턴스의 초안 소켓 세션이 2,000개 넘게 5분 지속",
     "2GB 인스턴스(힙 25%)는 2,000~2,500 접속에서 메모리가 바닥난다(11/9 측정, 힙 50% 면 3,000). "
     "인스턴스를 늘리거나 힙 비율을 올린다 — 세션은 인스턴스마다 따로 세므로 max 로 본다."),
)


def provision(datasource_uid, contact_point):
    rules = []
    for suffix, title, pending, expression, summary, description in RULES:
        rules.append({
            "uid": "bbang-course-draft-" + suffix, "title": title, "condition": "B",
            "data": [
                {"refId": "A", "relativeTimeRange": {"from": 600, "to": 0}, "datasourceUid": datasource_uid,
                 "model": {"refId": "A", "expr": expression, "instant": True, "range": False, "intervalMs": 1000, "maxDataPoints": 43200}},
                {"refId": "B", "relativeTimeRange": {"from": 0, "to": 0}, "datasourceUid": "__expr__",
                 "model": {"refId": "B", "type": "threshold", "expression": "A",
                           "conditions": [{"evaluator": {"type": "gt", "params": [0]}, "operator": {"type": "and"},
                                           "reducer": {"type": "last", "params": []}, "query": {"params": ["B"]}, "type": "query"}]}},
            ],
            "noDataState": "OK", "execErrState": "Alerting", "for": pending,
            "annotations": {"summary": summary, "description": description,
                            "dashboard_url": "https://grafana.bbangmapbbang.shop/d/bbang-course-draft-v0",
                            "recovery_note": "Resolved 는 조건 해제다. 밀린 초안이 DB 에 들어갔는지는 backlog 0 과 flush 횟수 증가로 확인한다."},
            "labels": {"service": "course-draft", "environment": "prod", "severity": "warning"},
            "isPaused": True,
            "notification_settings": {"receiver": contact_point, "group_by": ["grafana_folder", "alertname"],
                                      "group_wait": "30s", "group_interval": "5m", "repeat_interval": "4h"},
        })
    return {"apiVersion": 1, "groups": [{"orgId": 1, "name": "course-draft-operations", "folder": "CourseDraft", "interval": "1m", "rules": rules}]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--datasource-uid", default="dfutc56whwh6of", help="운영 Prometheus 데이터소스 UID (dataops 와 같은 값)")
    parser.add_argument("--contact-point", default="slack-metric", help="기존 Grafana Slack contact point 이름 (운영 Grafana 에 있는 이름이어야 provisioning 이 통과한다)")
    parser.add_argument("--output", type=Path, default=ROOT / "monitoring/grafana/alerting/course-draft-rules.json")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(provision(args.datasource_uid, args.contact_point), ensure_ascii=False, indent=2) + "\n")
