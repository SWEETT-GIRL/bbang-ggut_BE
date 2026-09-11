package sweetgirl.croissant.course.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "courses")
@DynamicUpdate
// 지연 로딩 프록시를 IN 으로 묶어 읽는다 — 찜·참여 목록처럼 행마다 course SELECT 가 따로 나가던 N+1 을 막는다.
// 전역 default_batch_fetch_size 대신 이 엔티티에만 건다: 실측한 경로가 여기뿐이라 다른 엔티티의 페치는 종전과 같게 둔다.
@BatchSize(size = 100)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Course extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private CourseType type;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(length = 30)
	private String badge;

	/**
	 * 어드민이 지정하는 분류 — 탐색 탭의 필터 축 이름("대학가")과 그 안의 값("홍대").
	 * 시·도 필터는 가게 주소에서 파생하지만 대학가처럼 주소로 못 뽑는 묶음은 손으로 지정해야 한다.
	 * 축 이름을 앱에 박지 않고 값으로 갖는 건 다음 특집을 데이터만으로 늘리기 위해서다.
	 * 둘은 한 쌍이다 — 둘 다 있거나 둘 다 없다(호출부가 검증).
	 */
	@Column(name = "category_group", length = 20)
	private String categoryGroup;

	@Column(length = 20)
	private String category;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	@Column(nullable = false)
	private boolean isPublic;

	/**
	 * 공개 범위(PUBLIC / UNLISTED / PRIVATE). is_public 은 구버전 앱이 읽어 한 릴리즈 더 남기고,
	 * updateVisibility 가 PUBLIC 일 때만 true 로 같이 쓴다 — isPublic() 은 항상 (visibility == PUBLIC) 이다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private CourseVisibility visibility;

	/**
	 * 링크 공개(UNLISTED) 공유 토큰. 처음 UNLISTED 로 바꿀 때 발급하고 만료 없이 보존한다 — 왕복해도 이미 나간 링크가 살아 있어야 한다.
	 * 초대 토큰과 컬럼을 분리한다: 초대 토큰은 수락하면 편집자가 되고 이건 보기만 된다(유출 피해 등급이 다르다).
	 */
	@Column(name = "share_token", length = 64)
	private String shareToken;

	/** 빈 일차를 포함한 일차 수. 일행이 같은 일차 수를 보게 서버가 갖는다. 늘어나기만 한다. */
	@Column(name = "day_count", nullable = false)
	private int dayCount = 1;

	/**
	 * 폴링 ETag. 코스·가게·메모·멤버를 바꾸는 모든 쓰기가 CourseRepository.bumpVersion 으로 +1 한다.
	 * JPA @Version 이 아니다 — 그러면 모든 편집에 낙관적 락 충돌이 붙는다. 평범한 카운터다.
	 * JPA 쪽에서는 읽기 전용이다(insertable/updatable=false): Hibernate 는 UPDATE 에 전 컬럼을 쓰므로
	 * bumpVersion 뒤에 엔티티가 flush 되면 메모리의 낡은 값으로 되돌릴 수 있다. 쓰기는 bumpVersion 만 한다.
	 * 값은 DB 가 DEFAULT 0 으로 채우고, 같은 트랜잭션 안에서 올린 값은 refresh 전엔 안 보인다.
	 */
	@Column(nullable = false, insertable = false, updatable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
	private long version;

	@Column(name = "source_course_id")
	private Long sourceCourseId;

	@Column(name = "pin_order")
	private Integer pinOrder;

	/** 여행 계획 — 어느 지역(시·도)을 언제 가는지. 실제 방문(빵로그)과 대조하려고 만들 때 받는다. 구버전 앱은 null. */
	@Column(length = 20)
	private String region;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;

	private LocalDateTime deletedAt;

	@Builder(access = AccessLevel.PRIVATE)
	private Course(
			CourseType type,
			String title,
			Member member,
			boolean isPublic,
			String badge,
			String categoryGroup,
			String category,
			Long sourceCourseId,
			String region,
			LocalDate startDate,
			LocalDate endDate) {
		this.type = type;
		this.title = title;
		this.member = member;
		this.isPublic = isPublic;
		this.visibility = CourseVisibility.fromPublic(isPublic);
		this.badge = badge;
		this.categoryGroup = categoryGroup;
		this.category = category;
		this.sourceCourseId = sourceCourseId;
		this.region = region;
		this.startDate = startDate;
		this.endDate = endDate;
	}

	public static Course createCustom(
			String title,
			Member member,
			boolean isPublic) {
		return createCustom(title, member, isPublic, null, null, null);
	}

	public static Course createCustom(
			String title,
			Member member,
			boolean isPublic,
			String region,
			LocalDate startDate,
			LocalDate endDate) {
		return Course.builder()
			.type(CourseType.CUSTOM)
			.title(title)
			.member(member)
			.isPublic(isPublic)
			.region(region)
			.startDate(startDate)
			.endDate(endDate)
			.build();
	}

	public static Course createCurated(
			String title,
			String badge) {
		return createCurated(title, badge, null, null);
	}

	public static Course createCurated(
			String title,
			String badge,
			String categoryGroup,
			String category) {
		return Course.builder()
			.type(CourseType.CURATED)
			.title(title)
			.isPublic(true)
			.badge(badge)
			.categoryGroup(categoryGroup)
			.category(category)
			.build();
	}

	public static Course createForked(
			String title,
			Member member,
			Long sourceCourseId) {
		return Course.builder()
			.type(CourseType.FORKED)
			.title(title)
			.member(member)
			.isPublic(false)
			.sourceCourseId(sourceCourseId)
			.build();
	}

	public void updateTitle(String title) {
		this.title = title;
	}

	/**
	 * 공개 범위 전환. is_public 은 구버전 앱이 읽어 한 릴리즈 더 남긴다 — PUBLIC 일 때만 true 로 같이 쓴다.
	 * 그래서 isPublic() 은 항상 (visibility == PUBLIC) 이다. "공개일 때만" 판정하는 곳은 이 불변식에 기댄다.
	 */
	public void updateVisibility(CourseVisibility visibility) {
		this.visibility = visibility;
		this.isPublic = visibility == CourseVisibility.PUBLIC;
	}

	/** 공유 토큰은 한 번만 발급한다 — 공개 범위를 오가도 이미 나간 링크가 살아 있어야 한다. */
	public void issueShareToken(String shareToken) {
		if (this.shareToken == null) {
			this.shareToken = shareToken;
		}
	}

	/** 일차 수는 호출부가 검증한다(max(day) 이상, 현재 값 이상). 여기서는 값만 바꾼다. */
	public void updateDayCount(int dayCount) {
		this.dayCount = dayCount;
	}

	/** 가게가 더 뒤 일차에 놓이면 일차 수를 따라 올린다. 줄이지는 않는다. */
	public void ensureDayCountAtLeast(int day) {
		if (day > this.dayCount) {
			this.dayCount = day;
		}
	}

	/** 방장 승계 — 나가기·탈퇴 때 참여 순서가 가장 빠른 멤버에게 넘긴다. */
	public void changeOwner(Member newOwner) {
		this.member = newOwner;
	}

	public void updateBadge(String badge) {
		this.badge = badge;
	}

	/** 분류는 축과 값이 한 쌍이라 같이 바꾼다. 빈 문자열로 오면 호출부가 null 로 바꿔 분류를 뗀다. */
	public void updateCategory(String categoryGroup, String category) {
		this.categoryGroup = categoryGroup;
		this.category = category;
	}

	public void updateRegion(String region) {
		this.region = region;
	}

	public void updateDates(LocalDate startDate, LocalDate endDate) {
		this.startDate = startDate;
		this.endDate = endDate;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}

	public void pin(int pinOrder) {
		this.pinOrder = pinOrder;
	}

	public void unpin() {
		this.pinOrder = null;
	}

	public boolean isPinned() {
		return this.pinOrder != null;
	}

	public boolean isDeleted() {
		return this.deletedAt != null;
	}
}
