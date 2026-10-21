package sweetgirl.croissant.admin.bulkimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;
import sweetgirl.croissant.admin.bulkimport.repository.BulkImportProgressRepository;

@ExtendWith(MockitoExtension.class)
class BulkImportProgressServiceTest {

	@Mock
	BulkImportProgressRepository repository;

	@InjectMocks
	BulkImportProgressService service;

	@Test
	@DisplayName("findOrCreate: 존재하지 않으면 PENDING으로 생성한다")
	void findOrCreate_createsNew_whenNotExists() {
		given(repository.findByRegion1stAndRegion2ndAndRegion3rd("서울", "강남구", "역삼동"))
			.willReturn(Optional.empty());
		given(repository.save(org.mockito.ArgumentMatchers.any(BulkImportProgress.class)))
			.willAnswer(inv -> inv.getArgument(0));

		BulkImportProgress progress = service.findOrCreate("서울", "강남구", "역삼동");

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.PENDING);
		verify(repository).save(org.mockito.ArgumentMatchers.any(BulkImportProgress.class));
	}

	@Test
	@DisplayName("recoverStaleInProgress: IN_PROGRESS 상태를 PENDING으로 일괄 변경한다")
	void recoverStaleInProgress_resetsAllInProgressToPending() {
		given(repository.bulkUpdateStatus(BulkImportStatus.IN_PROGRESS, BulkImportStatus.PENDING))
			.willReturn(3);

		int count = service.recoverStaleInProgress();

		assertThat(count).isEqualTo(3);
		verify(repository).bulkUpdateStatus(BulkImportStatus.IN_PROGRESS, BulkImportStatus.PENDING);
	}

	@Test
	@DisplayName("getPendingOrFailed: PENDING과 FAILED 상태의 동을 모두 반환한다")
	void getPendingOrFailed_returnsBothStatuses() {
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(repository.findByStatusIn(List.of(BulkImportStatus.PENDING, BulkImportStatus.FAILED)))
			.willReturn(List.of(p1, p2));

		assertThat(service.getPendingOrFailed()).hasSize(2);
	}
}
