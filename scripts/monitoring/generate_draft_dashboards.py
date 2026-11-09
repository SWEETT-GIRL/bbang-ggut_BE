"""메모 초안 버퍼(Redis) Grafana 대시보드 JSON 생성. Grafana 나 운영 서버에 접속하지 않는다.

지표는 전부 core `MemoDraftMetrics` 가 내는 것이다. 이름은 Micrometer Prometheus 규칙 그대로 —
Timer `draft_flush_lag_seconds` 는 `_count/_sum/_max` 와 `quantile` 시계열, DistributionSummary `draft_flush_batch_size` 도 같다.
"""
import json
from pathlib import Path

from generate_dataops_dashboards import add, base, chart, text, variable

OUTPUT = Path(__file__).resolve().parents[2] / "monitoring" / "grafana"
UID = "bbang-course-draft-v0"
# 초안 PUT 하나만 본다 — 같은 컨트롤러의 GET drafts 는 화면 진입 때 한 번이라 503 비율을 흐린다
DRAFT_PUT = 'uri="/courses/{courseId}/stores/{storeId}/memo-draft",method="PUT"'


def draft_dashboard():
    dashboard = base(UID, "빵긋 · 빵투어 메모 초안 버퍼(Redis)", "DS_PROMETHEUS", "prometheus")
    dashboard["tags"] = ["bbanggut", "course-draft", "v0"]
    dashboard["time"] = {"from": "now-6h", "to": "now"}
    dashboard["templating"]["list"] = [
        variable("spring_job", "Spring scrape job", "label_values(draft_backlog, job)"),
        variable("spring_instance", "Spring 인스턴스", 'label_values(draft_backlog{job=~"$spring_job"}, instance)'),
    ]
    scope = '{job=~"${spring_job:regex}",instance=~"${spring_instance:regex}"}'
    put = scope[:-1] + "," + DRAFT_PUT + "}"
    put_503 = scope[:-1] + "," + DRAFT_PUT + ',status="503"}'
    rate = "[$__rate_interval]"
    text(dashboard, "먼저 읽기 · 초안 기능이 꺼진 인스턴스에는 이 지표가 없습니다",
         "`course.draft.redis.mode` 가 없는 환경(운영 v1)은 초안 빈이 하나도 뜨지 않아 **지표 자체가 없습니다** — no data 는 정상 0이 아닙니다. "
         "설계값: 마지막 입력 후 **2초** 조용하면 코스 단위로 DB 반영, 계속 치면 **10초** 상한. 그래서 플러시 지연 p95 가 2~10초인 것은 느린 게 아니라 의도입니다. "
         "`draft_backlog = -1` 은 초안 Redis 를 읽지 못한 것입니다. 503 은 앱이 기존 PATCH 저장으로 물러나는 경로라 유실이 아니지만, 지속되면 Redis·타임아웃을 봅니다.", 0, 4)
    chart(dashboard, "반영을 기다리는 코스 수 (backlog) · -1 = Redis 읽기 실패", "draft_backlog" + scope, 0, 4, kind="stat",
          description="Redis `draft:due` 크기. 서버가 살아 있으면 보통 0~몇 개. 계속 쌓이면 플러셔가 멈췄거나 DB 반영이 실패하고 있다.")
    chart(dashboard, "코스 신호 소켓 세션 (인스턴스별)", "draft_socket_sessions" + scope, 8, 4, kind="stat",
          description="이 인스턴스에 붙어 있는 STOMP 세션 수. 활성 멤버 2명 이상인 코스 화면을 연 사람 수에 가깝다.")
    chart(dashboard, "초안 Redis 접근 실패 (backlog -1 인 인스턴스 수)", "count(draft_backlog" + scope + " < 0) or vector(0)", 16, 4, kind="stat")
    chart(dashboard, "초안 쓰기 / 초 · accepted 받아들임 / stale 옛 seq 무시", "sum by (result) (rate(draft_write_total" + scope + rate + "))", 0, 11, unit="ops")
    dashboard["panels"][-1]["targets"][0]["legendFormat"] = "{{result}}"
    chart(dashboard, "합치기 비율 · 초안 쓰기 N번 → DB 반영 1번", "sum(increase(draft_write_total" + scope[:-1] + ',result="accepted"}' + rate + ")) / sum(increase(draft_flush_batch_size_count" + scope + rate + "))", 8, 11,
          description="dev 측정 기준 약 17:1. 1에 가까우면 치는 사람이 드물어 모을 게 없는 것이고(정상), 부하가 있는데 1이면 플러시가 너무 잦은 것.")
    chart(dashboard, "플러시 지연 p95 · 마지막 입력 → DB 반영", 'draft_flush_lag_seconds' + scope[:-1] + ',quantile="0.95"}', 16, 11, unit="s",
          description="설계 상한 10초. 그 위로 지속되면 플러셔 스레드가 막혔거나(긴 DB 반영) 임대 회수가 돌고 있다.")
    chart(dashboard, "DB 반영 횟수 / 분", "sum by (instance) (rate(draft_flush_total" + scope + rate + ")) * 60", 0, 18, unit="short")
    chart(dashboard, "임대 만료 회수 (플러셔가 죽었거나 반영이 임대보다 길었다)", "sum by (instance) (increase(draft_inflight_reclaimed_total" + scope + rate + "))", 8, 18,
          description="0이 정상. 값이 보이면 그 시각의 플러셔 로그 `[메모 초안] 임대가 만료된 코스` 를 본다.")
    chart(dashboard, "한 번에 반영한 초안 수 p95", 'draft_flush_batch_size' + scope[:-1] + ',quantile="0.95"}', 16, 18)
    chart(dashboard, "초안 PUT 503 비율", "sum by (instance) (rate(http_server_requests_seconds_count" + put_503 + rate + ")) / sum by (instance) (rate(http_server_requests_seconds_count" + put + rate + "))", 0, 25, unit="percentunit",
          description="503 = 초안 Redis 불가·타임아웃·권한 판정 DB 실패. 앱은 PATCH 로 물러난다. 요청이 없으면 no data.")
    chart(dashboard, "초안 PUT p95", "histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket" + put + rate + ")))", 8, 25, unit="s",
          description="PUT 은 DB 를 읽지 않는다(권한 10초 캐시). dev 측정에서 320명 부하에 p95 0.3~0.6초, 평소 수십 ms.")
    chart(dashboard, "초안 PUT 요청 / 초", "sum by (instance) (rate(http_server_requests_seconds_count" + put + rate + "))", 16, 25, unit="reqps")
    chart(dashboard, "Spring scrape 상태", 'up{job=~"${spring_job:regex}",instance=~"${spring_instance:regex}"}', 0, 32, kind="stat", width=24)
    return dashboard


if __name__ == "__main__":
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "course-draft-v0.json").write_text(json.dumps(draft_dashboard(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
