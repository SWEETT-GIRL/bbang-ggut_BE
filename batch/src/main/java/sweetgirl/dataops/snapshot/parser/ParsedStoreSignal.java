package sweetgirl.dataops.snapshot.parser;

import java.util.List;

/**
 * 스냅샷 payload 에서 뽑은 가게 신호 — 취향 태그·축 벡터의 소스 (코스 추천 1단계, 설계 §3 "가게 신호").
 * ParsedPlace(운영 반영용 필드)와 분리해 둔다 — 여기 필드는 canonical 해시·판정층에 들어가지 않는다.
 * 각 필드의 payload 위치는 StoreSignalParser 참조. 리스트는 비어 있을 수 있어도 null 은 아니다.
 * businessHours 는 ParsedPlace 와 같은 원문(newBusinessHours)을 BusinessHoursParser 로 구조화한 것 — 운영 store_business_hour 는
 * 5,831곳뿐이라 아침·저녁 시간 태그는 이쪽을 쓴다.
 */
public record ParsedStoreSignal(
		String category,
		String categoryCode,
		String description,
		List<String> keywordList,
		List<String> conveniences,
		List<String> microReviews,
		Double visitorReviewsScore,
		Integer visitorReviewsTotal,
		/** 별점을 매긴 리뷰 수 — visitorReviewsTotal(방문자 리뷰 수)과 다르다. 중앙값이 그 26% 뿐이라
		 * 평점의 신뢰도를 재려면 이쪽이 분모여야 한다(리뷰 590건인데 별점은 9건인 가게가 있다). */
		Integer ratingReviewsTotal,
		List<VotedKeyword> votedKeywords,
		List<ReviewMenu> reviewMenus,
		List<ReviewTheme> reviewThemes,
		List<RecommendMenu> recommendMenus,
		List<BroadcastInfo> broadcastInfos,
		boolean newOpening,
		List<TopPhoto> topPhotos,
		Integer topPhotosTotal,
		Integer totalImages,
		List<BlogReview> blogReviews,
		List<BusinessHourRow> businessHours) {

	/** 방문자 리뷰 투표 키워드 — visitorReviewStats.analysis.votedKeyword.details[] (code 는 안정 식별자, name 은 표시명) */
	public record VotedKeyword(String code, String name, int count) {
	}

	/** 리뷰에서 언급된 메뉴 — visitorReviewStats.analysis.menus[] */
	public record ReviewMenu(String label, int count) {
	}

	/** 리뷰 테마(맛·분위기·가격 …) — visitorReviewStats.analysis.themes[] */
	public record ReviewTheme(String code, String label, int count) {
	}

	/** 사장님 대표 메뉴 — Menu 엔티티 중 recommend=true. 세트 메뉴는 가격이 없어 null */
	public record RecommendMenu(String name, String price, String description, String imageUrl) {
	}

	/** 방송 출연 — placeDetail.broadcastInfos[] */
	public record BroadcastInfo(String program, String channel, String date, String menu) {
	}

	/** 상단 사진 — id 접두(business_/visitor_/video_/clip_)로 출처 구분, url 은 originalUrl */
	public record TopPhoto(String id, String url, String thumbnailUrl) {
	}

	/** 블로그 리뷰 요약 — placeDetail.fsasReviews.items[] (초기 응답에 최대 3건) */
	public record BlogReview(String title, String contents, String url, String date) {
	}
}
