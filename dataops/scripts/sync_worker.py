"""주기 재동기화 워커 — due 폴링 → 네이버 플레이스 fetch → 원본 제출.

판정하지 않는다. 파싱하지 않는다(__APOLLO_STATE__ 추출까지만). 원본 전달자다.

- due 목록(GET /api/sync-states/due)이 곧 워크리스트 — 별도 큐·클레임·복구 로직 없음
- 요청 간격 1.5초/req 고정 (실측 하한 0.67 req/s, 리서치 2.2 — 400회 무차단)
- fetch 실패는 POST /api/sync-states/{id}/failure 보고 → 서버가 백오프
- 차단 감지 시 exit 3 즉시 종료 — 안 긁힌 가게는 due 로 남아 다음 세션에 재등장한다
- 세션형: due 가 비면 exit 0. 언제 켜든 가장 밀린 가게부터, 도중에 꺼도 정리할 것 없음

사용:
    python scripts/sync_worker.py                          # localhost:8090, due 소진까지
    python scripts/sync_worker.py --max 100                # 표본 100곳만 (0단계 게이트)
    python scripts/sync_worker.py --server http://host:8090 --limit 50
"""

import argparse
import json
import os
import sys
import time
import uuid
import hashlib
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from html.parser import HTMLParser

import requests
from capture_spool import CaptureSpool
from hold_recovery import HoldRecovery, classify, receipt_reasons
from dataops_server import server_headers

REQUEST_INTERVAL_SECONDS = 1.5
APOLLO_MARKER = "window.__APOLLO_STATE__"
NAVER_PLACE_URL = "https://m.place.naver.com/restaurant/{place_id}/home"
MOBILE_USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
)

EXIT_OK = 0
EXIT_SERVER_ERROR = 1
EXIT_BLOCKED = 3


def fetch_due(server: str, limit: int, store_ids=None) -> list:
    params = {"limit": limit}
    if store_ids is not None:
        params["storeIds"] = ','.join(map(str, sorted(store_ids)))
    response = requests.get(f"{server}/api/sync-states/due", headers=server_headers(), params=params, timeout=10)
    response.raise_for_status()
    return response.json()["data"]


def report_failure(server: str, sync_state_id: int) -> None:
    requests.post(f"{server}/api/sync-states/{sync_state_id}/failure", headers=server_headers(), timeout=10).raise_for_status()


def submit_snapshot(server: str, store_id: int, source: str, payload: dict, *,
                    external_id: str, observation_id: str, observed_at: str,
                    attempt_seq: int | None = None) -> dict:
    """Submit one fetch; any transport retry must reuse this observation ID and timestamp.

    attempt_seq 는 이 항목을 받은 배부의 세대다 (v3 P2-1). 그대로 돌려보내면 서버가 이미 지나간 배부의
    늦은 도착을 가려낸다 — 그 사이 새 배부가 더 최신 원본을 가져왔을 수 있다.
    """
    body = {"storeId": store_id, "source": source, "payload": payload,
            "externalId": external_id, "observationId": observation_id,
            "observedAt": observed_at}
    if attempt_seq is not None:
        body["attemptSeq"] = attempt_seq
    response = requests.post(
        f"{server}/api/snapshots", headers=server_headers(),
        json=body,
        timeout=30,
    )
    response.raise_for_status()
    receipt = response.json()["data"]
    if (receipt.get("captureId") != observation_id
            or receipt.get("assessment") not in ("READY", "PARTIAL", "HELD")):
        raise ValueError("capture receipt does not confirm this observation")
    return receipt


def is_blocked(response: requests.Response) -> bool:
    return response.status_code in (403, 429) or "captcha" in response.url.lower()


def is_challenge_page(html: str) -> bool:
    """Inspect rendered text, not CAPTCHA libraries shipped on ordinary place pages."""
    class VisibleText(HTMLParser):
        def __init__(self):
            super().__init__()
            self.hidden = 0
            self.parts = []

        def handle_starttag(self, tag, attrs):
            if tag in ("script", "style", "template"):
                self.hidden += 1

        def handle_endtag(self, tag):
            if tag in ("script", "style", "template"):
                self.hidden = max(0, self.hidden - 1)

        def handle_data(self, data):
            if not self.hidden:
                self.parts.append(data)

    parser = VisibleText()
    parser.feed(html)
    visible = " ".join(parser.parts).lower()
    return any(marker in visible for marker in ("captcha", "접근이 제한", "비정상적인 접근"))


def extract_apollo_state(html: str, normalize: bool = True) -> dict | None:
    marker_index = html.find(APOLLO_MARKER)
    if marker_index < 0:
        return None
    brace_index = html.find("{", marker_index)
    if brace_index < 0:
        return None
    payload, _ = json.JSONDecoder().raw_decode(html, brace_index)
    return (strip_nul(payload) if normalize else payload) if isinstance(payload, dict) else None


def strip_nul(value):
    """문자열 속 NUL(U+0000) 제거 — PostgreSQL jsonb 가 거부해 스냅샷 INSERT 가 500 으로 죽는다
    (실측: 메뉴 description 에 섞여 들어옴, 2026-09-04 storeId 471222)"""
    if isinstance(value, str):
        return value.replace("\x00", "")
    if isinstance(value, list):
        return [strip_nul(item) for item in value]
    if isinstance(value, dict):
        return {key: strip_nul(item) for key, item in value.items()}
    return value


@dataclass
class FetchResult:
    payload: dict | None
    blocked: bool
    kind: str
    http_status: int | None = None
    body_digest: str | None = None
    source_payload: dict | None = None
    failure_excerpt: str | None = None

    def __iter__(self):
        # Preserve tuple-unpacking by registration/status collectors.
        yield self.payload
        yield self.blocked


def fetch_place(place_id: str) -> FetchResult:
    response = requests.get(
        NAVER_PLACE_URL.format(place_id=place_id),
        headers={"User-Agent": MOBILE_USER_AGENT, "Accept-Language": "ko-KR,ko;q=0.9"},
        timeout=15,
    )
    # 네이버는 Content-Type 에 charset 을 안 준다 — requests 가 ISO-8859-1 로 잘못 디코딩해
    # 한글이 전부 깨지므로(mojibake) UTF-8 을 명시해야 한다. response.text 직접 사용 금지.
    html = response.content.decode("utf-8", errors="replace")
    digest = hashlib.sha256(response.content).hexdigest()
    if is_blocked(response):
        return FetchResult(None, True, "BLOCKED", response.status_code, digest, failure_excerpt=html[:65536])
    if response.status_code != 200:
        return FetchResult(None, False, "HTTP_ERROR", response.status_code, digest, failure_excerpt=html[:65536])
    if len(response.content) > 8 * 1024 * 1024:
        return FetchResult(None, False, "RESPONSE_TOO_LARGE", response.status_code, digest)
    try:
        raw = extract_apollo_state(html, normalize=False)
        if raw is None:
            if is_challenge_page(html):
                return FetchResult(None, True, "BLOCKED", response.status_code, digest, failure_excerpt=html[:65536])
            return FetchResult(None, False, "APOLLO_MISSING", response.status_code, digest, failure_excerpt=html[:65536])
        return FetchResult(strip_nul(raw), False, "SUCCESS", response.status_code, digest, raw)
    except json.JSONDecodeError:
        position = max(0, html.find(APOLLO_MARKER))
        return FetchResult(None, False, "APOLLO_INVALID", response.status_code, digest,
                           failure_excerpt=html[position:position + 65536])


def submit_retained(server, record, auto_apply=False):
    request = record["request"]
    result = submit_snapshot(server, request["storeId"], request["source"], request["payload"],
                             external_id=request["externalId"], observation_id=request["observationId"],
                             observed_at=request["observedAt"], attempt_seq=request.get("attemptSeq"))
    if auto_apply:
        response = requests.post(f"{server}/api/snapshots/{request['observationId']}/auto-apply",
                                 headers=server_headers(), timeout=120)
        response.raise_for_status()
        applied = response.json()["data"]
        if (applied.get("captureId") != request["observationId"]
                or any(type(applied.get(key)) is not int or applied[key] < 0
                       for key in ("applied", "alreadyApplied", "held", "failed", "unknown"))):
            raise ValueError("automatic apply receipt does not confirm this observation")
        result = {**result, "automaticApply": applied}
    return result


def safe_server(server, auto_apply=False):
    response = requests.get(f"{server}/api/snapshots/capabilities", headers=server_headers(), timeout=10)
    response.raise_for_status()
    data = response.json()["data"]
    if (data.get("captureProtocol") != 1 or not data.get("wholeObservationHold")
            or data.get("menuScopedHold") is not True or data.get("writesEnabled") is not False):
        raise RuntimeError("server must support durable capture, menu-scoped hold and whole-error hold with serving writes disabled")
    if auto_apply and data.get("automaticApplyProtocol") != 1:
        raise RuntimeError("server must support capture-scoped automatic apply; refusing collection-only fallback")
    return data


def run(server: str, limit: int, max_stores: int | None,
        cooldown_minutes: int, max_cooldowns: int, max_hours: float | None = None, spool=None, summary_path=None,
        auto_apply=False, capabilities=None, store_ids=None) -> int:
    processed = changed = unchanged = failed = 0
    held = partial = 0
    recovery = None
    recovery_attempted = recovery_resolved = 0
    remaining = set(store_ids) if store_ids is not None else None
    if remaining is not None and (not remaining or len(store_ids) > 50 or any(type(x) is not int or x <= 0 for x in remaining)):
        raise ValueError('store IDs must contain 1-50 positive integers')
    applied_counts = {"auto_apply_" + key: 0 for key in ("applied", "already_applied", "held", "failed", "unknown")}
    def account(result):
        applied = result.get("automaticApply", {})
        for key, source in (("already_applied", "alreadyApplied"), ("applied", "applied"),
                            ("held", "held"), ("failed", "failed"), ("unknown", "unknown")):
            applied_counts["auto_apply_" + key] += applied.get(source, 0)
        if applied.get('failed', 0) or applied.get('unknown', 0):
            print(f"[worker] 운영 반영 중단 — capture={applied.get('captureId')} "
                  f"실패 {applied.get('failed', 0)}, 미확정 {applied.get('unknown', 0)}", flush=True)
    try:
        spool = spool or CaptureSpool()
        cooldowns_used = 0
        # 시간 상자 — 야간 수집이 아침까지 넘지 않게. 받은 배치를 다 못 돌면 남은 항목은 소프트 리스가 풀린 뒤 다시 due 가 된다
        deadline = time.monotonic() + max_hours * 3600 if max_hours else None
        # Complete lost-ack submissions before taking a new lease. Identity/timestamp are unchanged.
        for record in spool.pending():
            if remaining is not None and record['request']['storeId'] not in remaining:
                continue
            if deadline is not None and time.monotonic() >= deadline:
                return EXIT_SERVER_ERROR if remaining else EXIT_OK
            try:
                result = submit_retained(server, record, auto_apply=auto_apply)
                account(result)
                spool.acknowledge(record, result)
                if (auto_apply and record.get("recovery", {}).get("parentCaptureId")
                        and classify(result, receipt_reasons(result)) == "RESOLVED"):
                    recovery_resolved += 1
                if applied_counts["auto_apply_failed"] or applied_counts["auto_apply_unknown"]:
                    return EXIT_SERVER_ERROR
            except requests.RequestException:
                return EXIT_SERVER_ERROR
        # Existing receipts (including pre-upgrade holds) are automatically inventoried.
        # Acknowledged APPLIED outcomes are never replayed as a recovery operation.
        recovery = HoldRecovery(spool, time.time()) if auto_apply else None
        # Targeted repairs always obtain a fresh lease; unrelated recovery work stays in its normal cycle.
        recovery_due = recovery.due(time.time(), (capabilities or {}).get("automaticHoursPermissionsReady") is True) if recovery and remaining is None else []
        while True:
            if remaining is not None and not remaining:
                break
            if deadline is not None and time.monotonic() >= deadline:
                print(f"[worker] 시간 상자 {max_hours}시간 도달 — 새 배치를 받지 않고 끝낸다", flush=True)
                break
            # --max 보다 큰 배치를 받으면 초과분이 소프트 리스에 걸려 2시간 due 에서 사라진다
            batch_limit = limit if max_stores is None else max(1, min(limit, max_stores - processed))
            if recovery_due:
                due_list = [{**record["request"], "_recovery": record} for record in recovery_due[:batch_limit]]
                recovery_due = recovery_due[batch_limit:]
            else:
                try:
                    due_list = fetch_due(server, batch_limit) if remaining is None else fetch_due(server, min(batch_limit, 50), remaining)
                except requests.RequestException as error:
                    print(f"[worker] due 조회 실패 — 서버 확인 필요: {error}", flush=True)
                    return EXIT_SERVER_ERROR
            if not due_list:
                break

            for due in due_list:
                if max_stores is not None and processed >= max_stores:
                    due_list = []
                    break
                if deadline is not None and time.monotonic() >= deadline:
                    break
                if due["source"] != "NAVER" or not due.get("externalId"):
                    continue

                if remaining is not None:
                    if due['storeId'] not in remaining:
                        raise ValueError('server returned a store outside the selected scope')

                parent = due.get("_recovery")
                if parent is not None:
                    parent = recovery.reserve(parent, time.time())
                    if parent is None:
                        continue
                    recovery_attempted += 1
                processed += 1
                try:
                    fetched = fetch_place(due["externalId"])
                    apollo_state, blocked = fetched
                    if not isinstance(fetched, FetchResult):
                        fetched = FetchResult(apollo_state, blocked, "BLOCKED" if blocked else "SUCCESS")
                except requests.RequestException:
                    apollo_state, blocked = None, False
                    fetched = FetchResult(None, False, "TRANSPORT_ERROR")

                observation_id = str(uuid.uuid4())
                observed_at = datetime.now(timezone.utc).isoformat()
                body = {"storeId": due["storeId"], "source": due["source"], "payload": apollo_state,
                        "externalId": due["externalId"], "observationId": observation_id,
                        "observedAt": observed_at, "attemptSeq": due.get("attemptSeq")}
                metadata = asdict(fetched)
                metadata.pop("payload")
                record = spool.retain(observation_id, body, metadata,
                                      recovery=recovery.metadata(body, parent) if recovery else None)
                if recovery:
                    recovery.observe(record, time.time())

                if blocked:
                    if parent is not None:
                        # No extra challenge requests in the recovery loop; retain evidence and stop.
                        return EXIT_BLOCKED
                    cooldowns_used += 1
                    if cooldowns_used > max_cooldowns:
                        print(f"[worker] 쿨다운 {max_cooldowns}회 소진 — 종료. "
                              f"처리 {processed}, 변경 {changed}, 무변경 {unchanged}, 실패 {failed}", flush=True)
                        return EXIT_BLOCKED
                    # 이 가게는 기록하지 않는다(스탬프도 안 함) — due 로 남아 재개 후 다시 나온다
                    processed -= 1
                    print(f"[worker] 차단 감지 (storeId={due['storeId']}) — "
                          f"{cooldown_minutes}분 쿨다운 ({cooldowns_used}/{max_cooldowns}) 후 재개", flush=True)
                    cooldown_remaining = cooldown_minutes * 60 if deadline is None else max(0, deadline - time.monotonic())
                    time.sleep(min(cooldown_minutes * 60, cooldown_remaining))
                    break  # due 목록을 새로 받아 이어간다

                if apollo_state is None:
                    failed += 1
                    if parent is None:
                        try:
                            report_failure(server, due["syncStateId"])
                        except requests.RequestException:
                            return EXIT_SERVER_ERROR
                else:
                    # Fetch identity is independent of semantic hash and assigned only once per successful fetch.
                    try:
                        result = submit_retained(server, record, auto_apply=auto_apply)
                        account(result)
                        spool.acknowledge(record, result)
                        # A claim/fetch attempt is not completion. Blocked or failed stores stay unfinished.
                        if remaining is not None:
                            remaining.remove(due['storeId'])
                        if recovery:
                            acknowledged = spool.read(spool.path(observation_id))
                            recovery.observe(acknowledged, time.time())
                            if parent is not None and spool.read(spool.path(observation_id))["recovery"]["status"] == "RESOLVED":
                                recovery_resolved += 1
                        if result.get("assessment") == "HELD":
                            held += 1
                        elif result.get("assessment") == "PARTIAL":
                            partial += 1
                        elif result["changed"]:
                            changed += 1
                        else:
                            unchanged += 1
                        if applied_counts["auto_apply_failed"] or applied_counts["auto_apply_unknown"]:
                            return EXIT_SERVER_ERROR
                    except requests.RequestException as error:
                        failed += 1
                        print(f"[worker] 제출 실패 (storeId={due['storeId']}): {error}", flush=True)
                        return EXIT_SERVER_ERROR  # Preserve pending record; do not refetch under a new ID.

                if processed % 50 == 0:
                    print(f"[worker] 처리 {processed} — 변경 {changed} / 무변경 {unchanged} / 실패 {failed}", flush=True)
                time.sleep(REQUEST_INTERVAL_SECONDS)

            if max_stores is not None and processed >= max_stores:
                break

        print(f"[worker] 세션 종료 — 처리 {processed}, 변경 {changed}, 무변경 {unchanged}, 부분처리 {partial}, 보류 {held}, 실패 {failed}", flush=True)
        if auto_apply:
            print("[worker] 운영 반영 — " + ", ".join(f"{key}={value}" for key, value in applied_counts.items()), flush=True)
            print("[worker] 보류 복구 — recovery_* 가게 / hold_* 변경 후보: " + json.dumps(recovery.counts(), ensure_ascii=False), flush=True)
        if remaining:
            print(f"[worker] 선택 가게 미처리(리스·스케줄·상태 확인): {sorted(remaining)}", flush=True)
        return EXIT_SERVER_ERROR if remaining or applied_counts["auto_apply_failed"] or applied_counts["auto_apply_unknown"] else EXIT_OK
    finally:
        if summary_path:
            from batch_execution import atomic_json
            atomic_json(summary_path, {"attempted": processed, "changed": changed, "unchanged": unchanged,
                                       "partial": partial, "held": held, "failed": failed, **applied_counts,
                                       **(recovery.counts() if recovery else {}),
                                       **({"selected_store_ids": sorted(set(store_ids)), "unattempted_store_ids": sorted(remaining)} if remaining is not None else {}),
                                       "recovery_attempted": recovery_attempted, "recovery_resolved": recovery_resolved})



def main() -> int:
    argument_parser = argparse.ArgumentParser(description="빵긋 데이터 최신화 워커")
    argument_parser.add_argument("--server", default="http://localhost:8090")
    argument_parser.add_argument("--limit", type=int, default=50, help="due 배치 크기")
    argument_parser.add_argument("--max", type=int, default=None, dest="max_stores",
                                 help="이번 세션 최대 처리 가게 수 (표본 실행용)")
    argument_parser.add_argument("--cooldown-minutes", type=int, default=45)
    argument_parser.add_argument("--max-cooldowns", type=int, default=8)
    argument_parser.add_argument("--max-hours", type=float, default=None,
                                 help="이 시간이 지나면 새 가게를 받지 않고 끝낸다 (야간 수집 시간 상자)")
    argument_parser.add_argument("--summary", help="실제 처리 결과 JSON 경로")
    argument_parser.add_argument("--store-ids", type=int, nargs='+', help="선택 가게만 새 리스로 재수집 (최대 50곳, 기존 due/리스 조건 유지)")
    arguments = argument_parser.parse_args()
    automatic = os.environ.get('DATAOPS_AUTO_APPLY_ENABLED', 'false')
    if automatic not in ('true', 'false'):
        argument_parser.error('DATAOPS_AUTO_APPLY_ENABLED must be true or false')
    auto_apply = automatic == 'true'
    if arguments.limit < 1 or (arguments.max_stores is not None and arguments.max_stores < 1) or (arguments.max_hours is not None and arguments.max_hours <= 0):
        argument_parser.error("limit/max/max-hours must be positive")
    try:
        capabilities = safe_server(arguments.server, auto_apply=auto_apply)
        spool = CaptureSpool()
        with spool.exclusive():
            return run(arguments.server, arguments.limit, arguments.max_stores,
                       arguments.cooldown_minutes, arguments.max_cooldowns, arguments.max_hours, spool, arguments.summary,
                       auto_apply=auto_apply, capabilities=capabilities, store_ids=arguments.store_ids)
    except (OSError, ValueError, RuntimeError, requests.RequestException) as error:
        print(f"[worker] 실행 보류: {type(error).__name__}", flush=True)
        return EXIT_SERVER_ERROR


if __name__ == "__main__":
    sys.exit(main())
