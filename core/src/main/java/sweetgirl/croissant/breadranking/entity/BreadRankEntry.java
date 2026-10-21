package sweetgirl.croissant.breadranking.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.store.entity.Store;

/**
 * 내 빵 순위 한 줄 — 회원 × 카테고리 × 가게. {@code rank = 1} 이 곧 그 카테고리의 한 표라 표 테이블은 따로 없다.
 *
 * <p>등수는 이어서 센다(1, 2, 2, 3). 공동을 허용하고, null 은 담기만 하고 아직 안 정한 줄이다.
 * 식감·맛·또 먹을래요·메모는 여기 두지 않는다 — 그 회원의 그 가게 × 카테고리 빵로그 태그 평가 중 최신을 읽는다.
 */
@Entity
@Table(name = "bread_rank_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRankEntry extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long memberId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id", nullable = false)
	private BreadCategory category;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	/** 1부터. null = 아직 안 정함 */
	@Column(name = "rank")
	private Integer rank;

	/** 등수를 마지막으로 정한 시각 — 전체 순위의 동점 정렬("가장 최근 표") */
	private LocalDateTime rankedAt;

	private BreadRankEntry(Long memberId, BreadCategory category, Store store) {
		this.memberId = memberId;
		this.category = category;
		this.store = store;
	}

	/** 담기만 — 등수는 비워 둔다. */
	public static BreadRankEntry unranked(Long memberId, BreadCategory category, Store store) {
		return new BreadRankEntry(memberId, category, store);
	}

	public boolean isRanked() {
		return rank != null;
	}

	public boolean isTop() {
		return rank != null && rank == 1;
	}

	/** 등수를 정한다 — 재정렬로 번호만 옮기는 것과 달리 시각을 새로 찍는다. */
	public void place(int rank, LocalDateTime now) {
		this.rank = rank;
		this.rankedAt = now;
	}

	/** 재정렬 — 다른 줄이 끼거나 빠져서 번호만 바뀐 것. 시각은 그대로. */
	public void renumber(int rank) {
		this.rank = rank;
	}
}
