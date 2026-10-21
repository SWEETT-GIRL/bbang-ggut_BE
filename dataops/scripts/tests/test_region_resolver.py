"""공통 법정동 resolver — 스펙 FR-11 의 경계 fixture. 네트워크·DB 없음(실제 기준표 파일만 읽는다)."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bjd_reference  # noqa: E402
import region_resolver as rr  # noqa: E402

REF = bjd_reference.load()


def b(code, *names, address=None):
    doc = {"region_type": "B", "code": code}
    for i, name in enumerate(names, 1):
        doc[f"region_{i}depth_name"] = name
    doc["address_name"] = address or " ".join(n for n in names if n)
    return doc


def h(code, address):
    return {"region_type": "H", "code": code, "address_name": address}


class RegionResolverTest(unittest.TestCase):
    def test_투영_v2_는_N가를_접어_동으로_묶고_원형은_근거에_남긴다(self):
        # 2026-09-26 결정: 앱 지역은 ~동으로 묶는다 — 성수동1가·2가 → 성수동
        r = rr.resolve([b("1120011500", "서울특별시", "성동구", "성수동2가")], REF)
        self.assertEqual(r["status"], "RESOLVED")
        self.assertEqual((r["region_1st"], r["region_2nd"], r["region_3rd"]), ("서울", "성동구", "성수동"))
        # 원형 이름과 코드는 잃지 않는다
        self.assertEqual(r["hierarchy"]["eupmyeondong"]["name"], "성수동2가")
        self.assertEqual(r["bjd_code"], "1120011500")

    def test_대구_동인동4가는_동인동으로_묶인다(self):
        r = rr.resolve([b("2711010400", "대구광역시", "중구", "동인동4가")], REF, {"대구_중구_동인동"})
        self.assertEqual(r["region_3rd"], "동인동")
        self.assertEqual(r["be_region3rd_key"], "대구_중구_동인동")

    def test_거리형_법정동은_접은_이름_그대로이고_동을_덧붙이지_않는다(self):
        # 을지로동은 행정동 이름이다 — 붙이면 H 를 B 자리에 쓰는 셈
        r = rr.resolve([b("1114010400", "서울특별시", "중구", "을지로1가")], REF)
        self.assertEqual(r["region_3rd"], "을지로")

    def test_N가_접기는_끝의_숫자가만_뗀다(self):
        self.assertEqual(rr.fold_ga("가양동"), "가양동")
        self.assertEqual(rr.fold_ga("한강로3가"), "한강로")
        self.assertEqual(rr.fold_ga("동인동4가"), "동인동")
        self.assertEqual(rr.fold_ga("정관읍"), "정관읍")

    def test_시_구_계층은_코드로_검증하고_2번째는_시로_투영한다(self):
        r = rr.resolve([b("4113510300", "경기도", "성남시 분당구", "정자동")], REF)
        self.assertEqual(r["status"], "RESOLVED")
        self.assertEqual(r["hierarchy"]["sigungu"]["name"], "성남시 분당구")
        self.assertEqual((r["region_1st"], r["region_2nd"], r["region_3rd"]), ("경기", "성남시", "정자동"))

    def test_세종은_시도_행_없이_시군구_행이_시도다(self):
        r = rr.resolve([b("3611025000", "세종특별자치시", "", "조치원읍",
                          address="세종특별자치시 조치원읍")], REF)
        self.assertEqual(r["status"], "RESOLVED")
        # 승인된 관행: 세종은 region_2nd 도 읍면동
        self.assertEqual((r["region_1st"], r["region_2nd"], r["region_3rd"]), ("세종", "조치원읍", "조치원읍"))

    def test_리는_계층에만_남기고_투영에서_뺀다(self):
        # 리 단위 코드 — 기준표에서 존재하는 리 하나를 찾아 쓴다
        code = next(c for c, e in REF._by_code.items()
                    if e.exists and REF.has_ri(c) and c.startswith("51"))
        entry = REF.get(code)
        parts = entry.full_name.split()
        r = rr.resolve([b(code, *parts, address=entry.full_name)], REF)
        self.assertEqual(r["status"], "RESOLVED", r)
        self.assertIsNotNone(r["hierarchy"]["ri"])
        self.assertNotEqual(r["region_3rd"], r["hierarchy"]["ri"]["name"])

    def test_동명_지역은_시도까지_코드로_가려진다(self):
        # 서울 중구 을지로1가(1114010400)와 부산 중구 중앙동1가(2611010400)는 구 이름·뒷자리가 같다
        seoul = rr.resolve([b("1114010400", "서울특별시", "중구", "을지로1가")], REF)
        busan = rr.resolve([b("2611010400", "부산광역시", "중구", "중앙동1가")], REF)
        self.assertEqual((seoul["status"], seoul["region_1st"]), ("RESOLVED", "서울"))
        self.assertEqual((busan["status"], busan["region_1st"]), ("RESOLVED", "부산"))
        # 부산 코드에 서울 이름을 붙이면 코드 단위 대조에서 어긋난다 — (구, 동) 이름만 맞춰서는 통과 못한다
        crossed = rr.resolve([b("2611010400", "서울특별시", "중구", "을지로1가")], REF)
        self.assertEqual((crossed["status"], crossed["reason"]), ("UNRESOLVED", "HIERARCHY_MISMATCH"))

    def test_폐지된_옛_코드는_해소가_아니다(self):
        # 강원도(42) → 강원특별자치도(51) 개편 전 코드
        r = rr.resolve([b("4211000000", "강원도", "춘천시", "")], REF)
        self.assertEqual(r["status"], "UNRESOLVED")
        self.assertEqual(r["reason"], "ABOLISHED_CODE")

    def test_행정동만_있으면_법정동_대신_쓰지_않는다(self):
        r = rr.resolve([h("1120065000", "서울특별시 성동구 성수1가2동")], REF)
        self.assertEqual(r["status"], "UNRESOLVED")
        self.assertEqual(r["region_type"], "H")
        self.assertIsNone(r["bjd_code"])
        self.assertEqual(r["h_code"], "1120065000")

    def test_행정동_코드는_법정동_칸에_들어가지_않는다(self):
        r = rr.resolve([b("1120011500", "서울특별시", "성동구", "성수동2가"),
                        h("1120065000", "서울특별시 성동구 성수1가2동")], REF)
        self.assertEqual(r["bjd_code"], "1120011500")
        self.assertEqual(r["h_code"], "1120065000")
        self.assertEqual(r["hierarchy"]["h"]["code"], "1120065000")

    def test_코드와_이름이_어긋나면_해소가_아니다(self):
        r = rr.resolve([b("1120011500", "서울특별시", "성동구", "성수동1가")], REF)
        self.assertEqual((r["status"], r["reason"]), ("UNRESOLVED", "HIERARCHY_MISMATCH"))

    def test_기준표에_없는_코드는_해소가_아니다(self):
        r = rr.resolve([b("1199999999", "서울특별시", "가상구", "가상동")], REF)
        self.assertEqual(r["reason"], "CODE_NOT_IN_REFERENCE")

    def test_시군구까지만_풀리면_해소가_아니다(self):
        r = rr.resolve([b("1120000000", "서울특별시", "성동구", "")], REF)
        self.assertEqual((r["status"], r["reason"]), ("UNRESOLVED", "NO_EUPMYEONDONG"))

    def test_BE_어휘에_없어도_투영_규칙_밖으로_더_자르지_않는다(self):
        # 투영은 버전 있는 규칙(N가 접기)까지만이다 — BE enum 에 맞추려고 더 자르거나 다른 이름으로 바꾸지 않는다
        r = rr.resolve([b("4825013100", "경상남도", "김해시", "율하동")], REF, {"김해시_장유동"})
        self.assertEqual(r["region_3rd"], "율하동")
        self.assertIsNone(r["be_region3rd_key"])   # 소비자 준비도만 기록한다

    def test_시도_약칭표는_승인된_값의_규칙과_같다(self):
        # 이미 승인된 store.region 16,765건과 같은 규칙이어야 섞였을 때 값이 갈리지 않는다
        source = (Path(__file__).resolve().parents[1] / "region_recompute.py").read_text(encoding="utf-8")
        for full, short in rr.SIDO_SHORT.items():
            self.assertIn(f'"{full}": "{short}"', source.replace("\n", " "), full)

    def test_7월_개편의_전남광주통합특별시가_풀리고_구에_따라_광주_전남으로_투영된다(self):
        # 08-13 파일엔 없던 코드 — API 스냅샷에는 2026-07-01 생성일로 있다
        gwangsan = rr.resolve([b("1233011500", "전남광주통합특별시", "광산구", "월계동")], REF)
        self.assertEqual(gwangsan["status"], "RESOLVED", gwangsan)
        self.assertEqual((gwangsan["region_1st"], gwangsan["region_2nd"], gwangsan["region_3rd"]),
                         ("광주", "광산구", "월계동"))
        mokpo_code = next(c for c, e in REF._by_code.items()
                          if e.full_name.startswith("전남광주통합특별시 목포시 ") and not REF.has_ri(c))
        mokpo = rr.resolve([b(mokpo_code, *REF.get(mokpo_code).full_name.split())], REF)
        self.assertEqual((mokpo["status"], mokpo["region_1st"], mokpo["region_2nd"]), ("RESOLVED", "전남", "목포시"))

    def test_7월_개편의_인천_서해구가_풀린다(self):
        r = rr.resolve([b("2827511100", "인천광역시", "서해구", "청라동")], REF)
        self.assertEqual((r["status"], r["region_2nd"], r["region_3rd"]), ("RESOLVED", "서해구", "청라동"))

    def test_개편으로_사라진_광주광역시_코드는_현존이_아니다(self):
        # 08-13 파일엔 '존재'였으나 현존 목록에는 없다 — 추측으로 새 코드에 옮기지 않는다
        r = rr.resolve([b("2920000000", "광주광역시", "광산구", "")], REF)
        self.assertEqual((r["status"], r["reason"]), ("UNRESOLVED", "CODE_NOT_CURRENT"))

    def test_상위_코드가_빈_API_행도_코드_자리로_계층을_찾는다(self):
        # 2026-09-26 스냅샷에서 locathigh_cd 가 빈 행 — 영덕군 영해면 대동리
        r = rr.resolve([b("4777036039", "경상북도", "영덕군", "영해면", "대동리")], REF)
        self.assertEqual(r["status"], "RESOLVED", r)
        self.assertEqual((r["region_1st"], r["region_2nd"], r["region_3rd"]), ("경북", "영덕군", "영해면"))

    def test_파이썬_버전과_서버_설정_버전이_같다(self):
        # 게이트는 설정의 버전으로 만든 근거만 인정한다 — 한쪽만 올리면 모든 위치 제안이 "근거 없음"으로 막힌다
        root = Path(__file__).resolve().parents[3] / "batch"
        for props in ("src/main/resources/dataops.properties", "src/test/resources/dataops.properties"):
            text = (root / props).read_text(encoding="utf-8")
            self.assertIn(f"dataops.region.reference-version={bjd_reference.REFERENCE_VERSION}", text, props)
            self.assertIn(f"dataops.region.projection-version={rr.PROJECTION_VERSION}", text, props)

    def test_좌표_지문은_소수_6자리(self):
        self.assertEqual(rr.fingerprint(127.0500001, 37.54), "127.050000,37.540000")
        self.assertNotEqual(rr.fingerprint(127.05, 37.54), rr.fingerprint(127.050002, 37.54))

    def test_기준표_해시가_다르면_멈춘다(self):
        with self.assertRaises(SystemExit):
            bjd_reference.load(expected_sha256="0" * 64)


if __name__ == "__main__":
    unittest.main()


class _FakeCursor:
    """latest_evidence 조회에 준비된 행을 돌려주고, INSERT 는 id 만 돌려준다."""

    def __init__(self, latest_row):
        self.latest_row = latest_row
        self.inserts = []
        self._next = None

    def execute(self, sql, params):
        if sql.lstrip().startswith("SELECT"):
            self._next = self.latest_row
        else:
            self.inserts.append(params)
            self._next = (9000 + len(self.inserts),)

    def fetchone(self):
        return self._next


class _FakeConn:
    def __init__(self, cursor):
        self._cursor = cursor
        self.commits = 0

    def cursor(self):
        return self._cursor

    def commit(self):
        self.commits += 1


class PointResolverTest(unittest.TestCase):
    """등록 워커의 좌표 근거 — 같은 좌표·같은 버전이면 다시 부르지 않고, 관측 실패는 판정으로 재사용하지 않는다."""

    SEONGSU = [b("1120011500", "서울특별시", "성동구", "성수동2가")]

    def _resolver(self, latest_row, calls):
        conn = _FakeConn(_FakeCursor(latest_row))

        def fake_kakao(session, key, lon, lat):
            calls.append((lon, lat))
            return self.SEONGSU, None
        original = rr.kakao_documents
        rr.kakao_documents = fake_kakao
        self.addCleanup(setattr, rr, "kakao_documents", original)
        return rr.PointResolver(conn, key="test", session=object(), ref=REF, be_keys=set()), conn

    def test_같은_좌표의_근거가_있으면_카카오를_다시_부르지_않는다(self):
        calls = []
        resolver, conn = self._resolver((700, "RESOLVED", None, "1120011500", "서울", "성동구", "성수동", None), calls)
        ev = resolver.resolve("CANDIDATE", "mg-1", "REGISTRATION", 127.05, 37.54)
        self.assertTrue(ev["reused"])
        self.assertEqual((ev["id"], ev["region_3rd"]), (700, "성수동"))
        self.assertEqual(calls, [])
        self.assertEqual(conn.cursor().inserts, [])

    def test_근거가_없으면_역산해_후보_근거로_남기고_커밋한다(self):
        calls = []
        resolver, conn = self._resolver(None, calls)
        ev = resolver.resolve("CANDIDATE", "mg-1", "REGISTRATION", 127.05, 37.54)
        self.assertFalse(ev["reused"])
        self.assertEqual((ev["status"], ev["region_3rd"], ev["bjd_code"]), ("RESOLVED", "성수동", "1120011500"))
        self.assertEqual(calls, [(127.05, 37.54)])
        params = conn.cursor().inserts[0]
        # 대상·목적·좌표 지문·버전이 서버 RegionEvidenceFinder 의 조회 키와 같다
        self.assertEqual(params[:3], ("CANDIDATE", "mg-1", "REGISTRATION"))
        self.assertEqual(params[6], "127.050000,37.540000")
        self.assertEqual((params[14], params[15]), (bjd_reference.REFERENCE_VERSION, rr.PROJECTION_VERSION))
        self.assertEqual(conn.commits, 1)

    def test_직전_호출이_카카오_오류였으면_다시_부른다(self):
        calls = []
        resolver, _ = self._resolver((701, "UNRESOLVED", "KAKAO_ERROR", None, None, None, None, None), calls)
        ev = resolver.resolve("CANDIDATE", "mg-1", "REGISTRATION", 127.05, 37.54)
        self.assertFalse(ev["reused"])
        self.assertEqual(len(calls), 1)
        self.assertEqual(ev["status"], "RESOLVED")


class RegisterItemTest(unittest.TestCase):
    def test_등록_요청은_지역을_싣지_않는다(self):
        # 지역은 서버가 좌표의 근거에서 찾는다 — 인허가 지번 3토큰은 검증이 아니다
        import localdata_register_worker as worker
        item = worker.register_item("mg-1", {"name": "빵집", "roadAddress": "", "phone": "", "lon": 127.0,
                                             "lat": 37.5}, "서울특별시 성동구 성수동2가 1", "상호")
        self.assertNotIn("region1st", item)
        self.assertNotIn("bjdCode", item)
        self.assertEqual(item["address"], "서울특별시 성동구 성수동2가 1")
        self.assertEqual((item["longitude"], item["latitude"]), (127.0, 37.5))
