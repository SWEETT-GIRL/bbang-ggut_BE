package sweetgirl.dataops.snapshot.parser;

import static org.assertj.core.api.Assertions.*;

import java.io.InputStream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class StoreSignalParserTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final StoreSignalParser storeSignalParser = new StoreSignalParser(new NaverPlaceParser(),
		new BusinessHoursParser());

	/** 실제 스냅샷 축소판 — 성수동 르모멍(store 465987, placeId 2008297455), 2026-09 크롤. 태그 소스 필드만 남겼다 */
	private JsonNode lemomentFixture() {
		try (InputStream in = getClass().getResourceAsStream("/fixtures/apollo_state_lemoment_465987.json")) {
			return objectMapper.readTree(in);
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void 실제_스냅샷에서_신호_필드를_전부_뽑는다() {
		ParsedStoreSignal signal = storeSignalParser.parse(lemomentFixture());

		assertThat(signal.category()).isEqualTo("베이커리");
		assertThat(signal.description()).startsWith("프렌치 베이커리 르모멍입니다.");
		assertThat(signal.keywordList()).containsExactly("케이크", "선물", "답례품", "베이커리", "고구마휘낭시에");
		assertThat(signal.conveniences()).containsExactly("포장", "예약", "무선 인터넷", "간편결제");
		assertThat(signal.microReviews()).containsExactly("특별한 날 어울리는 시즌 케이크");
		assertThat(signal.visitorReviewsScore()).isEqualTo(4.9);
		assertThat(signal.visitorReviewsTotal()).isEqualTo(129);
		assertThat(signal.newOpening()).isFalse();
		assertThat(signal.broadcastInfos()).isEmpty();
	}

	@Test
	void visitorReviewStats_참조를_해소해_투표키워드_리뷰메뉴_테마를_읽는다() {
		ParsedStoreSignal signal = storeSignalParser.parse(lemomentFixture());

		assertThat(signal.votedKeywords()).isNotEmpty();
		assertThat(signal.votedKeywords().get(0).code()).isEqualTo("bread_good");
		assertThat(signal.votedKeywords().get(0).name()).isEqualTo("빵이 맛있어요");
		assertThat(signal.votedKeywords().get(0).count()).isPositive();
		assertThat(signal.reviewMenus().get(0).label()).isEqualTo("케이크");
		assertThat(signal.reviewThemes().get(0).code()).isEqualTo("taste");
		assertThat(signal.reviewThemes().get(0).label()).isEqualTo("맛");
	}

	@Test
	void 대표메뉴_상단사진_블로그리뷰를_읽는다() {
		ParsedStoreSignal signal = storeSignalParser.parse(lemomentFixture());

		assertThat(signal.recommendMenus()).hasSize(8);
		assertThat(signal.recommendMenus()).extracting(ParsedStoreSignal.RecommendMenu::name)
			.contains("파운드 케이크 세트", "플랑");
		// 선물세트 2종은 가격이 "" 로 와서 null 로 정규화된다
		assertThat(signal.recommendMenus()).filteredOn(menu -> menu.price() == null).hasSize(2);
		assertThat(signal.recommendMenus()).allSatisfy(menu -> assertThat(menu.imageUrl()).startsWith("https://"));
		assertThat(signal.topPhotos()).hasSize(3);
		assertThat(signal.topPhotos()).allSatisfy(photo -> {
			assertThat(photo.id()).isNotBlank();
			assertThat(photo.url()).startsWith("https://");
		});
		assertThat(signal.topPhotosTotal()).isEqualTo(56);
		assertThat(signal.totalImages()).isEqualTo(16);
		assertThat(signal.blogReviews()).hasSize(1);
		assertThat(signal.blogReviews().get(0).title()).contains("르모멍");
		assertThat(signal.blogReviews().get(0).url()).startsWith("https://m.blog.naver.com/");
	}

	@Test
	void 리뷰_없는_가게는_analysis가_인라인_null이라_빈_리스트다() {
		// 신규 가게 실측: visitorReviewStats 가 참조가 아니라 인라인 객체이고 analysis:null, informationTab 없음
		JsonNode apolloState = objectMapper.readTree("""
				{
				  "ROOT_QUERY": {
				    "__typename": "Query",
				    "placeDetail({\\"input\\":{\\"id\\":\\"1\\"}})": {
				      "base": {"__ref": "PlaceDetailBase:1"},
				      "description": null,
				      "visitorReviewStats": {"__typename": "VisitorReviewStatsResult", "analysis": null},
				      "broadcastInfos": [{"program": "생활의달인", "channel": "SBS", "date": "24.10.14.", "menu": "감자쫀득빵"}],
				      "newOpening": true,
				      "topPhotos": {"items": [], "total": 0},
				      "fsasReviews": {"items": [], "total": 0}
				    }
				  },
				  "PlaceDetailBase:1": {
				    "__typename": "PlaceDetailBase",
				    "category": "카페,디저트",
				    "conveniences": null,
				    "visitorReviewsScore": "",
				    "visitorReviewsTotal": 0
				  },
				  "Menu:1_0": {"__typename": "Menu", "name": "소금빵", "price": "3500", "recommend": false, "images": []}
				}
				""");

		ParsedStoreSignal signal = storeSignalParser.parse(apolloState);

		assertThat(signal.category()).isEqualTo("카페,디저트");
		assertThat(signal.description()).isNull();
		assertThat(signal.keywordList()).isEmpty();
		assertThat(signal.conveniences()).isEmpty();
		assertThat(signal.visitorReviewsScore()).isNull();
		assertThat(signal.visitorReviewsTotal()).isZero();
		assertThat(signal.votedKeywords()).isEmpty();
		assertThat(signal.reviewMenus()).isEmpty();
		assertThat(signal.reviewThemes()).isEmpty();
		assertThat(signal.recommendMenus()).isEmpty();
		assertThat(signal.topPhotos()).isEmpty();
		assertThat(signal.blogReviews()).isEmpty();
		assertThat(signal.newOpening()).isTrue();
		assertThat(signal.broadcastInfos()).hasSize(1);
		assertThat(signal.broadcastInfos().get(0).program()).isEqualTo("생활의달인");
		assertThat(signal.broadcastInfos().get(0).menu()).isEqualTo("감자쫀득빵");
	}

	@Test
	void 영업시간을_운영_반영_파서와_같은_위치에서_구조화한다() {
		ParsedStoreSignal signal = storeSignalParser.parse(lemomentFixture());

		assertThat(signal.businessHours()).isNotEmpty();
		assertThat(signal.businessHours()).anySatisfy(row -> {
			assertThat(row.dayOfWeek()).isNotBlank();
			assertThat(row.closed() || row.openTime() != null).isTrue();
		});
	}
}
