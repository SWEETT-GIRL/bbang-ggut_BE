/**
 * 초안 버퍼 k6 시나리오 공용 — 데이터 로딩·VU→(코스, 멤버, 가게) 배정·seq 시계·운영 차단.
 *
 * courses.csv: courseId,storeIds(|구분),memberIds(|구분)   (seed-draft-courses.sql 주석의 쿼리로 뽑는다)
 * tokens.csv:  memberId,token                              (scripts/perf/generate-tokens.mjs)
 */
import {SharedArray} from 'k6/data';
import exec from 'k6/execution';

export const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
if (baseUrl.includes('bbang-ggut.cloud') || (baseUrl.includes('bbang-ggut.site') && __ENV.ALLOW_STAGING !== 'true')) {
  throw new Error('운영·dev 는 때리지 않는다. 로컬 랩에서만 돈다.');
}

// k6 의 open() 은 이 스크립트 파일 위치 기준이다 — 데이터 파일은 같은 폴더에 둔다
const dir = __ENV.DATA_DIR || '.';

export const courses = new SharedArray('courses', () =>
  open(`${dir}/courses.csv`).trim().split('\n').filter(Boolean).map((line) => {
    const [courseId, storeIds, memberIds] = line.split(',');
    return {
      courseId: Number(courseId),
      storeIds: storeIds.split('|').map(Number),
      memberIds: memberIds.split('|').map(Number),
    };
  }),
);

const tokenByMember = new SharedArray('tokens', () =>
  open(`${dir}/tokens.csv`).trim().split('\n').filter(Boolean).map((line) => {
    const [memberId, token] = line.split(',');
    return {memberId: Number(memberId), token};
  }),
);
// VU 마다 전체 토큰 색인을 만들면 수천 VU 에서 메모리가 터진다(12,000토큰 × VU 수). 공유 배열을 이분 탐색하고 VU 는 자기 것만 기억한다.
const tokenCache = {};
function tokenOf(memberId) {
  if (tokenCache[memberId]) return tokenCache[memberId];
  let lo = 0, hi = tokenByMember.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1; const row = tokenByMember[mid];
    if (row.memberId === memberId) { tokenCache[memberId] = row.token; return row.token; }
    if (row.memberId < memberId) lo = mid + 1; else hi = mid - 1;
  }
  return undefined;
}

export function assignment(vu) {
  vu = vu + Number(__ENV.VU_OFFSET || 0); // 생성기·프로세스를 나눌 때 회원이 겹치지 않게
  const membersPerCourse = Number(__ENV.MEMBERS_PER_COURSE || 8);
  const courseIndex = Math.floor((vu - 1) / membersPerCourse) % courses.length;
  const course = courses[courseIndex];
  const member = course.memberIds[(vu - 1) % Math.min(membersPerCourse, course.memberIds.length)];
  return {course, memberId: member, token: tokenOf(member)};
}

/** 앱의 seq 시계 — max(직전 + 1, 서버에서 본 최대 + 1, 현재 ms). 필드마다 하나. */
export function nextSeq(state, key, seenFromServer) {
  const previous = state[key] || 0;
  const seq = Math.max(previous + 1, (seenFromServer || 0) + 1, Date.now());
  state[key] = seq;
  return seq;
}

export function headers(token) {
  return {'Content-Type': 'application/json', Authorization: `Bearer ${token}`};
}

/** 램프업 단계 — 깨지는 지점을 찾는다. STAGES 로 덮어쓸 수 있다("30s:40,60s:80,..."). */
export function rampStages() {
  const spec = __ENV.STAGES || '30s:16,60s:16,30s:40,60s:40,30s:80,60s:80,30s:160,60s:160,30s:320,60s:320';
  return spec.split(',').map((part) => {
    const [duration, target] = part.split(':');
    return {duration, target: Number(target)};
  });
}

export function memoText(prefix) {
  return `${prefix} ${Date.now()} ${Math.random().toString(36).slice(2, 8)}`;
}

/** 지금 램프업의 몇 번째 단계인가. 요청에 {stage} 태그로 붙여 단계별 p95 를 요약에서 본다. */
export function currentStage() {
  const elapsedMs = Date.now() - exec.scenario.startTime;
  let acc = 0;
  const stages = rampStages();
  for (let i = 0; i < stages.length; i++) {
    acc += Number(stages[i].duration.replace('s', '')) * 1000;
    if (elapsedMs < acc) {
      return `${i + 1}:${stages[i].target}`;
    }
  }
  return `${stages.length}:${stages[stages.length - 1].target}`;
}

/** 단계별 p95·에러율 문턱 — 깨지는 단계가 요약에 ✗ 로 드러난다. */
export function stageThresholds() {
  const thresholds = {memo_save_duration: ['p(95)<500'], memo_save_failed: ['rate<0.01']};
  rampStages().forEach((stage, i) => {
    thresholds[`memo_save_duration{stage:${i + 1}:${stage.target}}`] = ['p(95)<500'];
    thresholds[`memo_save_failed{stage:${i + 1}:${stage.target}}`] = ['rate<0.01'];
  });
  return thresholds;
}
