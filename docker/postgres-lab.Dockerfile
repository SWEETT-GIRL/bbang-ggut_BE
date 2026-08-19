# 로컬 랩용 PostgreSQL — 운영 RDS(PostgreSQL 18 + PostGIS 3.6)와 같은 조합에
# 검색 실험용 pg_bigm(2-gram, RDS에도 1.2 제공)을 태그 고정 소스 빌드로 얹는다(재현성). pg_trgm은 contrib에 기본 포함.
# 공식 postgis/postgis:18-3.6 이미지는 amd64 전용이라 arm64(Apple Silicon)를 지원하는 imresamu 빌드를 쓴다.
# 사용: docker compose -f docker-compose.lab-postgres.yml up -d --build
FROM imresamu/postgis:18-3.6

# libicu-dev: PG18 서버 헤더(pg_locale.h)가 unicode/ucol.h 를 include 하므로 확장 컴파일에 필요
RUN apt-get update \
	&& apt-get install -y --no-install-recommends build-essential git ca-certificates postgresql-server-dev-18 libicu-dev \
	&& git clone --depth 1 --branch v1.2-20250903 https://github.com/pgbigm/pg_bigm.git /tmp/pg_bigm \
	&& cd /tmp/pg_bigm \
	&& make USE_PGXS=1 \
	&& make USE_PGXS=1 install \
	&& rm -rf /tmp/pg_bigm /var/lib/apt/lists/*
