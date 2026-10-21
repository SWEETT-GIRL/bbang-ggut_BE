package sweetgirl.dataops.snapshot.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.snapshot.code.SnapshotErrorCode;
import sweetgirl.dataops.snapshot.dto.response.ParsedPlaceResponse;
import sweetgirl.dataops.snapshot.dto.response.SnapshotDetailResponse;
import sweetgirl.dataops.snapshot.dto.response.SnapshotResponse;
import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.repository.PlaceSnapshotRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SnapshotQueryService {

	private final PlaceSnapshotRepository placeSnapshotRepository;
	private final ObjectMapper objectMapper;
	private final NaverPlaceParser naverPlaceParser;

	public PageResponse<SnapshotResponse> getSnapshots(Long storeId, int page, int size) {
		return PageResponse.from(
			placeSnapshotRepository.findByStoreIdOrderByFetchedAtDesc(storeId, PageRequest.of(page, size))
				.map(SnapshotResponse::from));
	}

	public SnapshotDetailResponse getSnapshot(Long snapshotId) {
		PlaceSnapshot snapshot = placeSnapshotRepository.findById(snapshotId)
			.orElseThrow(() -> new SnapshotException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
		return SnapshotDetailResponse.of(snapshot, readPayload(snapshot));
	}

	/** 파싱 미리보기 — 저장된 원본을 실제 파서로 파싱해 보여준다 (눈검사용, 재파싱이므로 부수효과 없음) */
	public ParsedPlaceResponse getParsedSnapshot(Long snapshotId) {
		PlaceSnapshot snapshot = placeSnapshotRepository.findById(snapshotId)
			.orElseThrow(() -> new SnapshotException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
		return ParsedPlaceResponse.of(snapshot, ResponseDiagnostics.parsePreservingInput(naverPlaceParser, readPayload(snapshot)));
	}

	private JsonNode readPayload(PlaceSnapshot snapshot) {
		try {
			return objectMapper.readTree(snapshot.getPayload());
		} catch (JacksonException e) {
			throw new SnapshotException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND,
				"저장된 페이로드를 JSON 으로 읽지 못했습니다: " + e.getMessage());
		}
	}
}
