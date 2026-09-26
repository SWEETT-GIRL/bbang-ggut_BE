/**
 * 선착순 쿠폰 스파이크 측정 (설계: .claude/planning/feat-coupon-concurrency.md §5).
 *
 *   k6 run --out csv=scripts/perf/result-R1.csv scripts/perf/coupon-spike.js
 *
 * 환경변수: BASE_URL(기본 http://localhost:8080) · RATE(초당 도착 수, 기본 5000) ·
 *          DURATION(기본 10s) · STOCK(재고, 기본 100) · BACKGROUND_RATE(배경 조회, 기본 50)
 *
 * ## 왜 도착률(arrival-rate)인가
 * "5만 명이 몰린다"는 동시 실행 5만이 아니라 **초당 도착 5천이 10초**다. VU 기반으로 돌리면
 * 앞 요청이 끝나야 다음이 출발해 대기 큐가 절대 쌓이지 않는다 — 실제 스파이크와 정반대다.
 * constant-arrival-rate 는 응답이 느려져도 도착률을 유지하려 VU 를 늘린다. 그래야 큐가 쌓이고,
 * 풀이 마르고, 타임아웃이 난다. 우리가 보려는 게 바로 그 지점이다.
 *
 * ## 배경 조회 부하를 같이 넣는 이유
 * 쿠폰만 때리면 쿠폰 API 가 죽는 것만 본다. 실제로는 Hikari 풀을 모든 API 가 공유하므로
 * 쿠폰이 풀을 다 먹으면 검색·지도까지 같이 죽는다. 그 전이(轉移)를 보려면 같이 돌려야 한다.
 */
import http from "k6/http";
import exec from "k6/execution";
import { Counter, Trend } from "k6/metrics";
import { SharedArray } from "k6/data";

const baseUrl = __ENV.BASE_URL || "http://localhost:8080";

// 운영에는 어떤 경우에도 쏘지 않는다.
if (baseUrl.includes("bbang-ggut.cloud")) {
  throw new Error("운영에는 돌리지 않는다.");
}
// 스테이징은 의도를 명시했을 때만. ALLOW_STAGING=true 를 손으로 붙여야 한다.
if (baseUrl.includes("bbang-ggut.site") && __ENV.ALLOW_STAGING !== "true") {
  throw new Error("스테이징을 때리려면 ALLOW_STAGING=true 를 주세요. 측정 중 스테이징은 쓸 수 없습니다.");
}

/** 미리 뽑아둔 `memberId,token` (scripts/perf/generate-tokens.mjs). VU 마다 복사되지 않게 SharedArray 로 읽는다. */
const tokens = new SharedArray("tokens", () =>
  open("./tokens.csv")
    .split("\n")
    .filter((line) => line.length > 0)
    .map((line) => {
      const comma = line.indexOf(",");
      return { memberId: line.slice(0, comma), token: line.slice(comma + 1) };
    }),
);

const rate = Number(__ENV.RATE || 5000);
const duration = __ENV.DURATION || "10s";
const backgroundRate = Number(__ENV.BACKGROUND_RATE || 50);

const outcomes = new Counter("coupon_outcome");
const issuedDuration = new Trend("coupon_issued_duration", true);
const rejectedDuration = new Trend("coupon_rejected_duration", true);

export const options = {
  // 409(소진·중복)는 설계대로 동작한 것이라 실패가 아니다. 503(붐빔)과 5xx 는 실패로 센다.
  scenarios: {
    coupon_spike: {
      executor: "constant-arrival-rate",
      rate,
      timeUnit: "1s",
      duration,
      preAllocatedVUs: Math.min(rate, 1000),
      maxVUs: Math.min(rate * 2, 10000),
      exec: "issueCoupon",
    },
    background_read: {
      executor: "constant-arrival-rate",
      rate: backgroundRate,
      timeUnit: "1s",
      duration: "40s",
      preAllocatedVUs: 20,
      maxVUs: 200,
      exec: "browseStores",
    },
  },
  thresholds: {
    // 쿠폰이 폭주해도 검색은 살아 있어야 한다는 것이 이번 측정의 판정 기준 하나다.
    "http_req_duration{scenario:background_read}": ["p(99)<1000"],
  },
  // 요약에 p99 를 넣는다 — 기본 요약에는 없다.
  summaryTrendStats: ["avg", "p(50)", "p(95)", "p(99)", "max"],
};

/**
 * setup 에서 쿠폰을 초기화하고 id 를 받아 모든 VU 에 넘긴다 — 회차 사이에 사람 손이 끼지 않게.
 *
 * 리셋에도 토큰을 실어 보낸다. SecurityConfig 는 `GET /dev/**` 만 열어 두는데 이건 POST 다.
 * 설정에 `POST /dev/**` 허용을 더하면 그 규칙이 운영에도 실려 나가므로, 토큰을 쓰는 쪽을 택했다
 * (엔드포인트 자체는 `@Profile("local")` 이라 운영에는 존재하지 않는다).
 */
export function setup() {
  const stock = Number(__ENV.STOCK || 100);

  // 리셋 엔드포인트를 못 쓰는 환경(이미 준비된 쿠폰으로 잴 때)은 COUPON_ID 로 건너뛴다.
  if (__ENV.COUPON_ID) {
    const preflightOnly = http.get(searchUrl);
    if (preflightOnly.status !== 200) {
      throw new Error(`배경 조회가 부하 전에도 ${preflightOnly.status} 입니다.`);
    }
    console.log(`쿠폰 초기화 건너뜀 — COUPON_ID=${__ENV.COUPON_ID} 를 그대로 씁니다`);
    return { couponId: Number(__ENV.COUPON_ID), stock };
  }

  const response = http.post(`${baseUrl}/dev/coupons/reset?totalQuantity=${stock}`, null, {
    headers: {
      Authorization: `Bearer ${tokens[0].token}`,
      // local 밖에서는 대시보드 키가 필요하다 (DevCouponController.validateAccess).
      "X-Perf-Dashboard-Key": __ENV.PERF_KEY || "",
    },
  });
  if (response.status !== 200) {
    throw new Error(
      `쿠폰 초기화 실패 (${response.status}). 앱이 local 프로파일로 떠 있는지, 토큰이 만료되지 않았는지 확인하세요.`,
    );
  }
  const data = response.json("data");

  // 배경 부하가 부하 전에도 성공하는지 먼저 확인한다. 여기서 막지 않으면 400 만 받아 놓고
  // "검색 p99 가 13초"라는 잘못된 결론을 내게 된다 — 실제로 한 번 그랬다.
  const preflight = http.get(searchUrl);
  if (preflight.status !== 200) {
    throw new Error(
      `배경 조회(${searchUrl})가 부하 전에도 ${preflight.status} 입니다. 이대로 재면 검색 지표가 거짓이 됩니다.`,
    );
  }

  console.log(`측정용 쿠폰 준비: couponId=${data.couponId}, 재고 ${data.totalQuantity}장, 직전 발급 ${data.deletedIssues}건 삭제`);
  console.log(`배경 조회 사전 확인: ${preflight.status} (${preflight.timings.duration.toFixed(1)}ms)`);
  return { couponId: data.couponId, stock: data.totalQuantity };
}

export function issueCoupon(setupData) {
  // iterationInTest 는 시나리오 전체에서 유일하다 — 회원이 겹치면 1인 1매에 걸려 측정이 왜곡된다.
  const row = tokens[exec.scenario.iterationInTest % tokens.length];

  const response = http.post(`${baseUrl}/coupons/${setupData.couponId}/issue`, null, {
    headers: { Authorization: `Bearer ${row.token}` },
    tags: { name: "issue" }, // URL 에 couponId 가 들어가 집계가 흩어지는 것을 막는다
  });

  let outcome;
  if (response.status === 0) {
    // 서버에 도달조차 못했다. 톰캣 accept 큐가 넘쳤다는 뜻이다.
    outcome = `conn_${response.error_code}`;
  } else if (response.status === 201) {
    outcome = "issued";
    issuedDuration.add(response.timings.duration);
  } else {
    const code = response.json("code");
    outcome = code || `http_${response.status}`;
    rejectedDuration.add(response.timings.duration, { outcome });
  }
  outcomes.add(1, { outcome });
}

/**
 * 배경 조회 부하. 쿼리를 반드시 인코딩한다 — 한글을 날것으로 붙이면 톰캣이 URI 단계에서
 * 400(HTML 에러 페이지)으로 거부한다. 앱까지 가지도 않으므로 "검색이 느려졌다"가 아니라
 * "검색을 아예 안 했다"가 되고, 그걸 모르면 측정값을 잘못 읽는다. (R1 1차에서 실제로 겪었다)
 */
const searchUrl = `${baseUrl}/stores/search?query=${encodeURIComponent("소금빵")}`;

export function browseStores() {
  http.get(searchUrl, { tags: { name: "search" } });
}

export function handleSummary(data) {
  const line = (label, value) => `  ${label.padEnd(28)} ${value}`;
  const metric = (name, stat) => {
    const m = data.metrics[name];
    return m && m.values[stat] !== undefined ? `${m.values[stat].toFixed(1)}ms` : "-";
  };

  const text = [
    "",
    "=== 쿠폰 스파이크 결과 ===",
    line("보낸 요청", data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0),
    line("발급 p99", metric("coupon_issued_duration", "p(99)")),
    line("거절 p99", metric("coupon_rejected_duration", "p(99)")),
    line("검색 p99 (배경)", metric("http_req_duration", "p(99)")),
    "",
    "결과별 건수는 --out csv 로 받은 파일에서 초 단위로 본다:",
    "  scripts/perf/summarize.sh <result.csv>",
    "",
  ].join("\n");

  return { stdout: text };
}
