package sweetgirl.croissant.breadlog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "store_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorePlan extends BaseTimeEntity {

	@EmbeddedId
	private StorePlanId id;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@MapsId("storeId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@Column(nullable = false, length = 200)
	private String memo;

	@Builder
	public StorePlan(Member member, Store store, String memo) {
		this.id = new StorePlanId(member.getId(), store.getId());
		this.member = member;
		this.store = store;
		this.memo = memo;
	}

	public void updateMemo(String memo) {
		this.memo = memo;
	}
}
