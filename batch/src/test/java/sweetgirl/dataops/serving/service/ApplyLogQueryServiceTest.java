package sweetgirl.dataops.serving.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import sweetgirl.dataops.global.response.PageResponse;
import sweetgirl.dataops.serving.dto.response.ApplyLogResponse;
import sweetgirl.dataops.serving.entity.ApplyLog;
import sweetgirl.dataops.serving.enums.ApplyOrigin;
import sweetgirl.dataops.serving.repository.ApplyLogRepository;

@ExtendWith(MockitoExtension.class)
class ApplyLogQueryServiceTest {

	@Mock
	private ApplyLogRepository applyLogRepository;

	@InjectMocks
	private ApplyLogQueryService applyLogQueryService;

	private ApplyLog createApplyLog() {
		return ApplyLog.builder()
			.storeId(1L)
			.target("store.phone")
			.oldValue("\"02-111-2222\"")
			.newValue("\"02-333-4444\"")
			.origin(ApplyOrigin.AUTO)
			.snapshotId(7L)
			.build();
	}

	@Test
	void getApplyLogs_storeId_지정_시_해당_가게만_조회한다() {
		given(applyLogRepository.findByStoreIdOrderByAppliedAtDesc(eq(1L), any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(createApplyLog()), PageRequest.of(0, 20), 1));

		PageResponse<ApplyLogResponse> response = applyLogQueryService.getApplyLogs(1L, 0, 20);

		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0).target()).isEqualTo("store.phone");
		assertThat(response.content().get(0).origin()).isEqualTo(ApplyOrigin.AUTO);
		then(applyLogRepository).should(never()).findAllByOrderByAppliedAtDesc(any(Pageable.class));
	}

	@Test
	void getApplyLogs_storeId_생략_시_전체를_조회한다() {
		given(applyLogRepository.findAllByOrderByAppliedAtDesc(any(Pageable.class)))
			.willReturn(new PageImpl<>(List.of(createApplyLog()), PageRequest.of(0, 20), 1));

		PageResponse<ApplyLogResponse> response = applyLogQueryService.getApplyLogs(null, 0, 20);

		assertThat(response.totalElements()).isEqualTo(1);
		then(applyLogRepository).should(never())
			.findByStoreIdOrderByAppliedAtDesc(anyLong(), any(Pageable.class));
	}
}
