# OCI Functions 용 이미지. 함수 애플리케이션의 아키텍처(shape)와 같은 아키텍처에서 빌드해야 한다 —
# sharp 가 네이티브 바이너리를 쓴다. 운영 서버(arm64)에서 빌드하면 애플리케이션은 GENERIC_ARM 이어야 한다.
FROM node:22-slim AS deps
WORKDIR /function
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

FROM node:22-slim
WORKDIR /function
ENV NODE_ENV=production
COPY --from=deps /function/node_modules ./node_modules
COPY package.json func.js resizer.js ./
# Functions 는 컨테이너를 루트가 아닌 계정으로, 읽기 전용 파일시스템에서 돌린다.
RUN chmod -R o+rX /function
ENTRYPOINT ["node", "func.js"]
