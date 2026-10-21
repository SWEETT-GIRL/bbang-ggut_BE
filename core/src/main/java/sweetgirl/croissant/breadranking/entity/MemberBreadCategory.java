package sweetgirl.croissant.breadranking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;

/** 내 빵 순위의 탭 — 회원이 자기 순위에 추가한 카테고리와 그 순서. */
@Entity
@Table(name = "member_bread_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberBreadCategory extends BaseTimeEntity {

	@EmbeddedId
	private MemberBreadCategoryId id;

	@MapsId("categoryId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "category_id", nullable = false)
	private BreadCategory category;

	@Column(nullable = false)
	private int sortOrder;

	private MemberBreadCategory(Long memberId, BreadCategory category, int sortOrder) {
		this.id = new MemberBreadCategoryId(memberId, category.getId());
		this.category = category;
		this.sortOrder = sortOrder;
	}

	/** 맨 뒤에 붙인다 — sortOrder 는 지금 탭 수. */
	public static MemberBreadCategory append(Long memberId, BreadCategory category, int sortOrder) {
		return new MemberBreadCategory(memberId, category, sortOrder);
	}

	/** 탭 순서 다시 매기기 — 앞 탭을 빼면 뒤가 당겨진다. */
	public void moveTo(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public Long getMemberId() {
		return id.getMemberId();
	}
}
