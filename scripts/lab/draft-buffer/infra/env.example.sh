# 운영 VPC 실험 스크립트가 읽는 환경 변수. 복사해서 채운 뒤 `source` 한다 — 채운 파일은 커밋하지 않는다.
#   cp env.example.sh env.sh && $EDITOR env.sh && source env.sh
export DRAFT_LAB_WORK=        # 작업 폴더: api-*.jar · lab-k6/(k6 스크립트 · tokens.csv · courses.csv) · redis_pw.txt(600) 가 있는 곳. 결과 로그도 여기 쌓인다
export DRAFT_LAB_REPO=        # 이 레포 경로 (토큰 생성 scripts/perf/generate-tokens.mjs 를 부른다)
export DRAFT_LAB_KEY=         # bastion · 실험 서버 공용 pem 경로
export DRAFT_LAB_BASTION=     # bastion 공인 IP
export DRAFT_LAB_APP=         # 측정 대상 앱 서버(앱 + PostgreSQL) 사설 IP
export DRAFT_LAB_GEN=         # 부하 발생기(k6 · python3) 사설 IP
export DRAFT_REDIS_1=         # 초안 Redis 마스터 + 감시자
export DRAFT_REDIS_2=         # 복제본 + 감시자 (앱 2대 실험에서는 두 번째 앱 · 두 번째 발생기로도 쓴다)
export DRAFT_REDIS_3=         # 감시자 (두 번째 발생기로도 쓴다)
export DRAFT_MONITORING=      # Grafana 가 도는 모니터링 서버 사설 IP
