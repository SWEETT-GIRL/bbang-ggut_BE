package sweetgirl.croissant.admin.bulkimport.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;

class BulkImportProgressTest {

	@Test
	@DisplayName("새로 생성하면 status가 PENDING이고 count가 0이다")
	void newProgress_isPendingWithZeroCounts() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.PENDING);
		assertThat(progress.getFoundCount()).isZero();
		assertThat(progress.getInsertedCount()).isZero();
	}

	@Test
	@DisplayName("markInProgress 호출 시 status가 IN_PROGRESS로 바뀌고 last_attempted_at이 갱신된다")
	void markInProgress_changesStatus() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		progress.markInProgress();

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.IN_PROGRESS);
		assertThat(progress.getLastAttemptedAt()).isNotNull();
	}

	@Test
	@DisplayName("markCompleted 호출 시 status가 COMPLETED로 바뀌고 카운트가 기록된다")
	void markCompleted_setsCountsAndStatus() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		progress.markCompleted(40, 35);

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.COMPLETED);
		assertThat(progress.getFoundCount()).isEqualTo(40);
		assertThat(progress.getInsertedCount()).isEqualTo(35);
	}

	@Test
	@DisplayName("markFailed 호출 시 status가 FAILED로 바뀌고 error_message가 저장된다")
	void markFailed_setsErrorMessageAndStatus() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		progress.markFailed("Kakao API timeout");

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.FAILED);
		assertThat(progress.getErrorMessage()).isEqualTo("Kakao API timeout");
	}

	@Test
	@DisplayName("resetToPending: IN_PROGRESS 상태를 다시 PENDING으로 되돌린다 (서버 재시작 복구용)")
	void resetToPending_setsStatusBackToPending() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");
		progress.markInProgress();

		progress.resetToPending();

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.PENDING);
	}
}
