package sweetgirl.dataops.reconcile.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.dataops.reconcile.enums.ProvenanceSource;

/**
 * 필드 단위 출처 기록 — "이 값은 누가 넣었나". field 는 'phone' | 'business_hours' | 'menu.{menuId}' 체계.
 * 사용자 기여 메뉴는 시딩 시 USER 로 소급 기록된다 (재수집이 사용자 기여를 덮어쓰지 않게 하는 선행조건).
 */
@Entity
@Table(name = "store_field_provenance")
@IdClass(StoreFieldProvenanceId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreFieldProvenance {

	@Id
	@Column(name = "store_id")
	private Long storeId;

	@Id
	@Column(length = 60)
	private String field;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ProvenanceSource source;

	@Column(name = "snapshot_id")
	private Long snapshotId;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Builder
	public StoreFieldProvenance(Long storeId, String field, ProvenanceSource source, Long snapshotId) {
		this.storeId = storeId;
		this.field = field;
		this.source = source;
		this.snapshotId = snapshotId;
		this.updatedAt = LocalDateTime.now();
	}

	public static String menuField(Long menuId) {
		return "menu." + menuId;
	}

	public void updateSource(ProvenanceSource newSource, Long newSnapshotId) {
		this.source = newSource;
		this.snapshotId = newSnapshotId;
		this.updatedAt = LocalDateTime.now();
	}
}
