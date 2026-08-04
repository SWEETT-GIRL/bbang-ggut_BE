package sweetgirl.croissant.breadlog.entity;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.entity.BaseTimeEntity;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@Entity
@Table(name = "bread_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BreadRecord extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "store_id")
	private Store store;

	@Column(nullable = false, length = 100)
	private String storeName;

	@Column(length = 100)
	private String regionKey;

	@Column(length = 500)
	private String memo;

	@Column(length = 100)
	private String lovedBread;

	@Column(nullable = false)
	private LocalDateTime eatenAt;

	private LocalDateTime deletedAt;

	@Builder
	public BreadRecord(
			Member member,
			Store store,
			String storeName,
			String regionKey,
			String memo,
			String lovedBread,
			LocalDateTime eatenAt) {
		this.member = member;
		this.store = store;
		this.storeName = storeName;
		this.regionKey = regionKey;
		this.memo = memo;
		this.lovedBread = lovedBread;
		this.eatenAt = eatenAt;
	}

	public void update(
			Store store,
			String storeName,
			String regionKey,
			String memo,
			String lovedBread,
			LocalDateTime eatenAt) {
		this.store = store;
		this.storeName = storeName;
		this.regionKey = regionKey;
		this.memo = memo;
		this.lovedBread = lovedBread;
		this.eatenAt = eatenAt;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}
}
