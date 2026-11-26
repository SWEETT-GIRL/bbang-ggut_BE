# 이미지 리사이저 (OCI Functions)

버킷에 올라온 원본을 320 · 640 · 1024 webp 로 줄여 `resized/{크기}/{원본 키}` 에 쓰고 BE 에 알린다.
AWS Lambda `bbanggut-prod-image-resizer` 를 옮긴 것이다.

```
앱 ─원본 PUT─▶ 버킷 ─객체 이벤트─▶ Events 규칙 ─▶ 이 함수 ─▶ resized/…  +  PATCH /callback/images/status
```

| 파일 | 역할 |
|---|---|
| `resizer.js` | 리사이즈 본체. 저장소 · 콜백을 주입받아 로컬에서 테스트된다 |
| `func.js` | OCI 배선 — 이벤트 해석, 리소스 프린시펄 저장소, 콜백 |
| `resizer.test.js` | `npm test` |

## Lambda 와 달라진 점

- **허용 목록으로 바꿨다.** `stores/ menus/ reports/ reviews/ courses/ bread-records/ menu-add-reports/` 만 리사이즈한다
  (BE 콜백이 받는 목록과 같다). Lambda 는 `resized/` · `world/` 만 빼고 버킷 전체를 처리했다.
- **콜백을 끄는 스위치**(`WEBHOOK_DISABLED=true`)가 있다. 컷오버 전 리사이즈만 검증할 때 쓴다.
- 저장소 인증은 키가 아니라 리소스 프린시펄이다.

## 한도

- 메모리는 128 · 256 · 512 · 1024 · 2048 · 3072MB 중 하나다. **3072 가 상한**이다.
  AWS 에서 원본 2.6~9MB 사진 일부는 3008MB 로도 죽고 10240MB 에서야 됐다(2026-11-26) — 그런 원본은 여기서도 실패한다.
- 타임아웃 상한 300초.
- 일시적 실패(throw)는 Events 가 다시 전달한다. 처리는 멱등이다.

## 배포

함수 애플리케이션의 아키텍처와 **같은 아키텍처에서 빌드**한다(sharp 가 네이티브 바이너리를 쓴다).
운영 서버(arm64)에서 빌드하고 애플리케이션은 `GENERIC_ARM` 으로 만든다.

```bash
# 운영 서버에서. Auth Token 은 콘솔 → 프로필 → Auth tokens 에서 발급한다
docker login nrt.ocir.io          # 사용자: <namespace>/<로그인 이메일>, 비밀번호: Auth Token
docker build -t nrt.ocir.io/<namespace>/bbanggut/image-resizer:<버전> .
docker push  nrt.ocir.io/<namespace>/bbanggut/image-resizer:<버전>
```

콘솔에서 한 번 만들어 두는 것

1. **동적 그룹** — `ALL {resource.type = 'fnfunc', resource.compartment.id = '<compartment OCID>'}`
2. **정책** — `allow dynamic-group <그룹> to manage objects in compartment <이름> where all {target.bucket.name='bbanggut-prod-images'}`
   (바꾼 정책이 함수에 반영되기까지 최대 15분 걸린다)
3. **Functions 애플리케이션**(GENERIC_ARM) → **함수**(위 이미지, 메모리 2048, 타임아웃 120)
4. **함수 설정** — `WEBHOOK_URL`, `WEBHOOK_SECRET` (또는 `WEBHOOK_DISABLED=true`)
5. **Events 규칙** — Object Storage 의 Object - Create · Object - Update, 속성 `bucketName=bbanggut-prod-images`, 동작은 이 함수
6. 버킷의 **Emit object events** 가 켜져 있어야 한다

## 아직 실측하지 못한 것

- 덮어쓰기가 `createobject` 로 오는지 `updateobject` 로 오는지 — 그래서 둘 다 받는다
- 객체 이름이 URL 인코딩돼 오는지 — 지금 키는 영문 · 숫자 · `/` · `-` 뿐이라 영향이 없다
