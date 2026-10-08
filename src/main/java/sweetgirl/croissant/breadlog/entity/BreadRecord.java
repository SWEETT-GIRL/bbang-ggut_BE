package sweetgirl.croissant.breadlog.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.breadlog.enums.RecordType;
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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RecordType recordType = RecordType.NORMAL;

	@Column(length = 60)
	private String tutorialTemplateCode;

	public boolean isTutorial() {
		return recordType == RecordType.TUTORIAL;
	}

	public void markTutorial(String templateCode) {
		this.recordType = RecordType.TUTORIAL;
		this.tutorialTemplateCode = templateCode;
		this.isPublic = false;
	}

	/** 사진·가게·작성일은 보존하고 개인 메모와 쌓을 빵만 바꾼다. */
	public void editTutorial(String memo, String towerBread) {
		this.memo = memo;
		this.towerBread = towerBread;
	}

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

	/** 구버전 앱 호환용 — lovedBreads의 첫 번째 값을 그대로 비춘다. */
	@Column(length = 100)
	private String lovedBread;

	/** 최고였던 빵들 — 태그로 붙인 빵 이름 중에서 고른다. 고른 순서를 지킨다. */
	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "bread_record_loved_bread", joinColumns = @JoinColumn(name = "record_id"))
	@OrderColumn(name = "sort_order")
	@Column(name = "name", nullable = false, length = 100)
	@BatchSize(size = 100)
	private List<String> lovedBreads = new ArrayList<>();

	/** 빵탑에 쌓은 빵. 기록을 지우면 같이 사라진다. 빵 종류가 늘 수 있어 값을 제한하지 않는다. */
	@Column(length = 20)
	private String towerBread;

	@Column(nullable = false)
	private LocalDateTime eatenAt;

	private LocalDateTime deletedAt;

	/** 공개하면 가게 상세에 쌓인다. 나만 본다고 생각하고 쓴 옛 기록이 있어 기본은 비공개다. */
	@Column(nullable = false)
	private boolean isPublic;

	@Builder
	public BreadRecord(
			Member member,
			Store store,
			String storeName,
			String regionKey,
			String memo,
			List<String> lovedBreads,
			String towerBread,
			LocalDateTime eatenAt,
			boolean isPublic) {
		this.member = member;
		this.store = store;
		this.storeName = storeName;
		this.regionKey = regionKey;
		this.memo = memo;
		this.towerBread = towerBread;
		this.eatenAt = eatenAt;
		this.isPublic = isPublic;
		replaceLovedBreads(lovedBreads);
	}

	public void update(
			Store store,
			String storeName,
			String regionKey,
			String memo,
			List<String> lovedBreads,
			String towerBread,
			LocalDateTime eatenAt) {
		this.store = store;
		this.storeName = storeName;
		this.regionKey = regionKey;
		this.memo = memo;
		this.eatenAt = eatenAt;
		// 안 보내면 지금 값을 그대로 둔다 — 빵탑 빵을 모르는 구버전 앱이 수정해도 쌓아둔 빵이 사라지지 않게.
		if (towerBread != null) {
			this.towerBread = towerBread;
		}
		replaceLovedBreads(lovedBreads);
	}

	private void replaceLovedBreads(List<String> names) {
		this.lovedBreads.clear();
		if (names != null) {
			this.lovedBreads.addAll(names);
		}
		this.lovedBread = this.lovedBreads.isEmpty() ? null : this.lovedBreads.get(0);
	}

	public void changeVisibility(boolean isPublic) {
		this.isPublic = isPublic;
	}

	public void softDelete() {
		this.deletedAt = LocalDateTime.now();
	}
}
