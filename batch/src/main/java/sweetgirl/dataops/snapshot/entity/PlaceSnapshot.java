package sweetgirl.dataops.snapshot.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.dataops.sync.enums.SyncSource;

/**
 * 원본층 — 파싱 전 원본(Apollo state 등)을 그대로 보관한다 (불변, append-only, 전이 메서드 없음).
 * 파서 버그가 있어도 재파싱으로 복구 가능한 것이 이 층의 존재 이유다.
 */
@Entity
@Table(name = "place_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaceSnapshot {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SyncSource source;

	@Column(name = "fetched_at", nullable = false)
	private LocalDateTime fetchedAt;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	/** content_hash 를 계산한 세대 — 과거 스냅샷의 해시를 새 의미로 읽지 않기 위해 남긴다 (v3 P2-1) */
	@Column(name = "content_hash_version", nullable = false)
	private short contentHashVersion = 1;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String payload;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Builder
	public PlaceSnapshot(Long storeId, SyncSource source, LocalDateTime fetchedAt,
			String contentHash, Short contentHashVersion, String payload) {
		this.storeId = storeId;
		this.source = source;
		this.fetchedAt = fetchedAt;
		this.contentHash = contentHash;
		this.contentHashVersion = contentHashVersion != null ? contentHashVersion : 1;
		this.payload = payload;
		this.createdAt = LocalDateTime.now();
	}
}
