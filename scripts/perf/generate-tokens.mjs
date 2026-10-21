/**
 * 부하 측정용 액세스 토큰을 미리 뽑아 CSV 로 만든다.
 *
 *   node scripts/perf/generate-tokens.mjs <회원id목록.txt> [출력.csv]
 *
 * 입력은 회원 id 가 한 줄에 하나씩 든 파일이다(seed-coupon-members.sql 로 만든 회원들).
 * 출력은 헤더 없이 `memberId,token` 한 줄씩 — k6 의 SharedArray 가 그대로 읽는다.
 *
 * 왜 미리 뽑아두나: JwtAuthenticationFilter 를 실제로 태워야 부하가 진짜다. 인증을 우회하면
 * 필터·시큐리티 구간이 측정에서 빠진다. 반대로 k6 안에서 5만 개를 서명하면 부하 생성기 CPU 를
 * 측정 대상이 아닌 일에 쓰게 된다. 그래서 밖에서 미리 만든다.
 *
 * **액세스 토큰은 1시간짜리다(jwt.access-token-expiry=3600000).** 만들고 바로 돌려야 한다.
 * 측정 중에 만료되면 전부 401 이 되어 회차가 통째로 날아간다.
 *
 * 서명은 JwtProvider 와 똑같이 맞춘다 — secret 을 Base64 디코딩한 키로 HMAC-SHA, subject 에 memberId.
 * 이 저장소의 secret 은 320비트라 jjwt 의 Keys.hmacShaKeyFor 가 HS256 을 고른다.
 * jti 는 넣지 않는다 — 필터가 보지 않고, 5만 줄이면 파일 크기에만 영향을 준다.
 */
import { createHmac } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

const PROPERTIES = "api/src/main/resources/application-local.properties";
const EXPIRY_KEY = "jwt.access-token-expiry";
const SECRET_KEY = "jwt.secret";

function readProperty(text, key) {
  const line = text.split("\n").find((l) => l.trimStart().startsWith(`${key}=`));
  return line ? line.slice(line.indexOf("=") + 1).trim() : null;
}

function base64url(buffer) {
  return Buffer.from(buffer).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function sign(memberId, key, issuedAtSeconds, expirySeconds) {
  const header = base64url(JSON.stringify({ alg: "HS256" }));
  const payload = base64url(
    JSON.stringify({ sub: String(memberId), iat: issuedAtSeconds, exp: issuedAtSeconds + expirySeconds }),
  );
  const signingInput = `${header}.${payload}`;
  return `${signingInput}.${base64url(createHmac("sha256", key).update(signingInput).digest())}`;
}

const [, , idFile, outFile = "scripts/perf/tokens.csv"] = process.argv;
if (!idFile) {
  console.error("사용: node scripts/perf/generate-tokens.mjs <회원id목록.txt> [출력.csv]");
  process.exit(1);
}

// 부하 생성기에는 레포가 없다 — JWT_SECRET 을 주면 properties 없이도 돌아야 한다.
// 무조건 읽으면 원격 박스에서 ENOENT 로 죽는다.
const properties = tryReadProperties();
const secret = process.env.JWT_SECRET ?? readProperty(properties, SECRET_KEY);
if (!secret) {
  console.error(`${SECRET_KEY} 를 찾지 못했습니다. JWT_SECRET 환경변수로 넘기세요.`);
  process.exit(1);
}

function tryReadProperties() {
  try {
    return readFileSync(PROPERTIES, "utf8");
  } catch {
    return "";
  }
}
const key = Buffer.from(secret, "base64");
const bits = key.length * 8;
if (bits < 256) {
  console.error(`secret 이 ${bits}비트입니다. HS256 에는 256비트 이상이 필요합니다.`);
  process.exit(1);
}
if (bits >= 384) {
  console.error(`secret 이 ${bits}비트라 jjwt 가 HS256 이 아닌 알고리즘을 고릅니다. 이 스크립트를 맞춰 고치세요.`);
  process.exit(1);
}

const expiryMillis = Number(readProperty(properties, EXPIRY_KEY) ?? 3600000);
const expirySeconds = Math.floor(expiryMillis / 1000);
const issuedAt = Math.floor(Date.now() / 1000);

const memberIds = readFileSync(idFile, "utf8")
  .split("\n")
  .map((line) => line.trim())
  .filter((line) => /^\d+$/.test(line));

if (memberIds.length === 0) {
  console.error(`${idFile} 에서 회원 id 를 하나도 읽지 못했습니다.`);
  process.exit(1);
}

const lines = memberIds.map((memberId) => `${memberId},${sign(memberId, key, issuedAt, expirySeconds)}`);
writeFileSync(outFile, `${lines.join("\n")}\n`);

const expiresAt = new Date((issuedAt + expirySeconds) * 1000);
console.log(`토큰 ${lines.length}개 → ${outFile}`);
console.log(`만료 ${expiresAt.toLocaleString("ko-KR")} — 그 전에 측정을 끝내세요.`);
