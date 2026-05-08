#!/bin/bash
# CLAUDE.md의 프로젝트 구조 섹션에 없는 도메인 패키지를 감지한다
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLAUDE_MD="$ROOT/CLAUDE.md"
SRC="$ROOT/src/main/java/sweetgirl/croissant"

missing=""
while IFS= read -r d; do
  domain=$(basename "$d")
  if ! grep -qF "$domain" "$CLAUDE_MD"; then
    missing="$missing $domain"
  fi
done < <(find "$SRC" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | grep -vE '/(common|config|global)$')

if [ -n "$missing" ]; then
  echo "{\"systemMessage\": \"CLAUDE.md 업데이트 필요 — 문서화되지 않은 도메인:$missing\"}"
fi
