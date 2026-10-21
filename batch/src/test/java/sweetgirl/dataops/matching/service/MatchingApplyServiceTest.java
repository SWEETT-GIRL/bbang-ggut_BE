package sweetgirl.dataops.matching.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import sweetgirl.dataops.matching.dto.request.MatchingProposalRequest;
import sweetgirl.dataops.matching.dto.response.MatchingApplyResult;
import sweetgirl.dataops.reconcile.entity.StoreFieldProvenance;
import sweetgirl.dataops.reconcile.repository.StoreFieldProvenanceRepository;
import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.dto.StoreCurrentValues;
import sweetgirl.dataops.serving.enums.ApplyOrigin;
import sweetgirl.dataops.serving.service.ApplyLogCommandService;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class MatchingApplyServiceTest {

	@Mock
	private ObjectProvider<ServingReader> servingReaderProvider;
	@Mock
	private ObjectProvider<ServingWriter> servingWriterProvider;
	@Mock
	private ServingReader servingReader;
	@Mock
	private ServingWriter servingWriter;
	@Mock
	private ApplyLogCommandService applyLogCommandService;
	@Mock
	private StoreFieldProvenanceRepository provenanceRepository;

	private MatchingApplyService matchingApplyService;

	@BeforeEach
	void setUp() {
		matchingApplyService = new MatchingApplyService(
			servingReaderProvider, servingWriterProvider, applyLogCommandService,
			provenanceRepository, new ObjectMapper());
	}

	private void givenServing() {
		given(servingReaderProvider.getIfAvailable()).willReturn(servingReader);
		given(servingWriterProvider.getIfAvailable()).willReturn(servingWriter);
	}

	private StoreCurrentValues store(long id) {
		return new StoreCurrentValues(id, "가게", "주소", null, "ACTIVE", null, null, null);
	}

	private MatchingProposalRequest request(MatchingProposalRequest.Item... items) {
		return new MatchingProposalRequest(List.of(items));
	}

	@Test
	void 반영은_선기록_후_DML_후_provenance_순서다() {
		givenServing();
		given(servingReader.findStore(1L)).willReturn(Optional.of(store(1L)));
		given(servingReader.hasNaverPlaceId(1L)).willReturn(false);
		given(servingWriter.updateStoreNaverPlaceId(1L, null, "12345")).willReturn(1);

		MatchingApplyResult result = matchingApplyService.applyBatch(
			request(new MatchingProposalRequest.Item(1L, "12345")));

		assertThat(result.applied()).isEqualTo(1);
		then(applyLogCommandService).should().recordBeforeApply(
			eq(1L), eq("store.naver_place_id"), isNull(), eq("\"12345\""),
			eq(ApplyOrigin.PROPOSAL), isNull(), isNull());
		then(provenanceRepository).should().save(any(StoreFieldProvenance.class));
	}

	@Test
	void 조회와_쓰기_사이에_다른_경로가_매칭했으면_0행이고_반영으로_세지_않는다() {
		// 선점 방지의 실제 잠금은 UPDATE 조건(naver_place_id 가 여전히 NULL)이다 — 사전 조회만으로는 막히지 않는다
		givenServing();
		given(servingReader.findStore(1L)).willReturn(Optional.of(store(1L)));
		given(servingReader.hasNaverPlaceId(1L)).willReturn(false);
		given(servingWriter.updateStoreNaverPlaceId(1L, null, "12345")).willReturn(0);

		MatchingApplyResult result = matchingApplyService.applyBatch(
			request(new MatchingProposalRequest.Item(1L, "12345")));

		assertThat(result.applied()).isZero();
		assertThat(result.skippedAlreadyMatched()).isEqualTo(1);
		then(provenanceRepository).should(never()).save(any(StoreFieldProvenance.class));
	}

	@Test
	void 그새_매칭된_가게는_덮지_않고_스킵한다_선점방지() {
		givenServing();
		given(servingReader.findStore(1L)).willReturn(Optional.of(store(1L)));
		given(servingReader.hasNaverPlaceId(1L)).willReturn(true);

		MatchingApplyResult result = matchingApplyService.applyBatch(
			request(new MatchingProposalRequest.Item(1L, "12345")));

		assertThat(result.skippedAlreadyMatched()).isEqualTo(1);
		assertThat(result.applied()).isZero();
		then(servingWriter).should(never()).updateStoreNaverPlaceId(anyLong(), isNull(), anyString());
		then(applyLogCommandService).shouldHaveNoInteractions();
	}

	@Test
	void 개별_실패는_격리되어_배치_전체를_막지_않는다() {
		givenServing();
		given(servingReader.findStore(anyLong())).willAnswer(inv -> Optional.of(store(inv.getArgument(0))));
		given(servingReader.hasNaverPlaceId(anyLong())).willReturn(false);
		// store 2 만 실패 — 인자 전체를 덮는 willAnswer 로 strict-stubbing 충돌 회피
		given(servingWriter.updateStoreNaverPlaceId(anyLong(), isNull(), anyString())).willAnswer(inv -> {
			if (inv.getArgument(0, Long.class) == 2L) {
				throw new RuntimeException("DB 오류");
			}
			return 1;
		});

		MatchingApplyResult result = matchingApplyService.applyBatch(request(
			new MatchingProposalRequest.Item(1L, "111"),
			new MatchingProposalRequest.Item(2L, "222"),
			new MatchingProposalRequest.Item(3L, "333")));

		assertThat(result.applied()).isEqualTo(2);
		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.failedStoreIds()).containsExactly(2L);
	}
}
