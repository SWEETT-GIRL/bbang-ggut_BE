package sweetgirl.croissant.store.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;

@Entity
@Table(name = "saved_stores", uniqueConstraints = {
	@UniqueConstraint(columnNames = {"member_id", "store_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedStore extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id", nullable = false)
	private Store store;

	@Builder(access = AccessLevel.PRIVATE)
	private SavedStore(
			Member member,
			Store store) {
		this.member = member;
		this.store = store;
	}

	public static SavedStore create(
			Member member,
			Store store) {
		return SavedStore.builder()
			.member(member)
			.store(store)
			.build();
	}
}
