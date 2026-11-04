/**
 * 초안 버퍼 k6 시나리오 공용 — 데이터 로딩·VU→(코스, 멤버, 가게) 배정·seq 시계·운영 차단.
 *
 * courses.csv: courseId,storeIds(|구분),memberIds(|구분)   (seed-draft-courses.sql 주석의 쿼리로 뽑는다)
 * tokens.csv:  memberId,token                              (scripts/perf/generate-tokens.mjs)
 */
import {SharedArray} from 'k6/data';

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
const tokenIndex = {};
for (const row of tokenByMember) {
  tokenIndex[row.memberId] = row.token;
}

/** VU 하나 = 한 코스의 한 멤버. 코스 i 의 멤버 j 는 VU 번호 i*8+j. 멤버가 8명이 아니면 있는 만큼 돈다. */
export function assignment(vu) {
  const membersPerCourse = Number(__ENV.MEMBERS_PER_COURSE || 8);
  const courseIndex = Math.floor((vu - 1) / membersPerCourse) % courses.length;
  const course = courses[courseIndex];
  const member = course.memberIds[(vu - 1) % Math.min(membersPerCourse, course.memberIds.length)];
  return {course, memberId: member, token: tokenIndex[member]};
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
