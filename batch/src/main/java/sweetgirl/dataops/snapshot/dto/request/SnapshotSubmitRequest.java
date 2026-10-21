package sweetgirl.dataops.snapshot.dto.request;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;
import sweetgirl.dataops.sync.enums.SyncSource;

/**
 * 워커 원본 제출 — payload 는 파싱 전 원본(Apollo state) 그대로. fetchedAt 생략 시 수신 시각.
 *
 * {@code attemptSeq} 는 이 원본을 가져오라고 배부받은 세대다 (v3 P2-1). 배부마다 1씩 오르므로,
 * 현재 세대보다 낮은 제출은 이미 지나간 배부의 늦은 도착이다 — 방문 카운터·스케줄·신호를 그 값으로 움직이지 않는다.
 * 생략하면 세대를 주장하지 않는 요청으로 다룬다(기존 워커 호환).
 */
public record SnapshotSubmitRequest(
		@NotNull Long storeId,
		@NotNull SyncSource source,
		@NotNull JsonNode payload,
		LocalDateTime fetchedAt,
		String observationId,
		String externalId,
		OffsetDateTime observedAt,
		Long attemptSeq) {

	/** 이전 워커/내부 호출은 관측 신뢰도를 주장하지 않는 레거시 요청으로 유지한다. */
	public SnapshotSubmitRequest(Long storeId, SyncSource source, JsonNode payload, LocalDateTime fetchedAt) {
		this(storeId, source, payload, fetchedAt, null, null, null, null);
	}
}
