# feat/search-miss-eval — 실패 검색어 해석 평가 루프

## Spec
- 실패 검색어 해석(`SearchMissKeywordInterpreter`) 프롬프트·모델을 바꿀 때마다 같은 평가셋으로 숫자를 낸다.
- 평가셋은 실제 검색어 + 실제 네이버 블로그 글(2026-11-20 수집, 운영과 같은 sort=sim·10개)로 고정한다. 지어낸 가게·글 금지.
- 채점은 운영 코드 기준: LLM 답(refersTo)과, 그 답을 `verify()`에 통과시킨 파이프라인 결과(STORE/NOT_A_STORE/UNRESOLVED)를 같이 본다.
  - 치명: 가게가 아닌데 STORE로 통과 / 다른 가게 이름으로 통과 — 0건이어야 한다
  - 놓침: 가게인데 STORE로 통과 못 함 — 사람 검토로 넘어가는 안전한 실패
- 여러 모델을 한 번에 돌려 정확도·치명·지연·1,000건당 비용을 표로 낸다.
- 관련 PR마다 CI에서 돌리고, 운영 모델이 기준(치명 0, refersTo 정확도 ≥ 0.80)을 못 넘으면 실패시킨다.

## Plan
| 파일 | 내용 |
|---|---|
| `src/main/resources/search-miss-interpret-prompt.txt` | 프롬프트 v3 (빵집이 아닌 가게는 UNKNOWN, 온라인 판매자 포함, 표기 여러 개면 가장 많은 글의 표기) |
| `SearchMissKeywordInterpreter` | `buildPrompt` 를 패키지 범위로 (평가가 운영과 같은 프롬프트를 쓰게) |
| `src/test/resources/searchmiss/eval-cases.json` | 평가셋 26건 |
| `src/test/java/.../external/llm/LlmTestAdapters` | 환경 변수 키로 세 벤더 어댑터를 만드는 테스트 도우미 |
| `src/test/java/.../searchmiss/service/SearchMissInterpreterEvalTest` | `@Tag("llm")` 평가 러너 + 리포트 + 게이트 |
| `.github/workflows/search-miss-eval.yml` | 관련 경로를 바꾼 PR에서 실행, 요약을 Step Summary 로 |

## 위험 요소
- 실제 API 비용: 26건 × 3회 × 모델 수. 후보 5개 기준 PR 한 번에 1달러 미만.
- LLM 비결정성: 게이트가 흔들릴 수 있어 3회 반복 평균으로 판단하고, 치명 0만 엄격하게 본다.
- 사고(thinking) 모델은 출력 토큰 상한 300에서 잘릴 수 있다 → 모델 표기 뒤 `@토큰수`로 상한을 따로 준다.
- 포크 PR 은 시크릿이 없어 건너뛴다(키 없음 → assume 로 skip).
