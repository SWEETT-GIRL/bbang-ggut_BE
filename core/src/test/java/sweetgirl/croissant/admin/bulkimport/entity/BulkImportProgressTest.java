package sweetgirl.croissant.admin.bulkimport.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.admin.bulkimport.enums.BulkImportStatus;

class BulkImportProgressTest {

	@Test
	@DisplayName("새로 생성하면 status가 PENDING이고 count가 0이며 region 필드가 저장된다")
	void newProgress_isPendingWithZeroCounts() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.PENDING);
		assertThat(progress.getFoundCount()).isZero();
		assertThat(progress.getInsertedCount()).isZero();
		assertThat(progress.getRegion1st()).isEqualTo("서울특별시");
		assertThat(progress.getRegion2nd()).isEqualTo("강남구");
		assertThat(progress.getRegion3rd()).isEqualTo("역삼동");
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
		progress.markInProgress();

		progress.markCompleted(40, 35);

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.COMPLETED);
		assertThat(progress.getFoundCount()).isEqualTo(40);
		assertThat(progress.getInsertedCount()).isEqualTo(35);
	}

	@Test
	@DisplayName("markFailed 호출 시 status가 FAILED로 바뀌고 error_message가 저장된다")
	void markFailed_setsErrorMessageAndStatus() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");
		progress.markInProgress();

		progress.markFailed("Kakao API timeout");

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.FAILED);
		assertThat(progress.getErrorMessage()).isEqualTo("Kakao API timeout");
	}

	@Test
	@DisplayName("resetToPending: IN_PROGRESS 상태를 다시 PENDING으로 되돌리고 errorMessage를 비운다")
	void resetToPending_setsStatusBackToPendingAndClearsError() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");
		progress.markInProgress();
		progress.markFailed("Kakao API timeout");

		progress.resetToPending();

		assertThat(progress.getStatus()).isEqualTo(BulkImportStatus.PENDING);
		assertThat(progress.getErrorMessage()).isNull();
	}

	@Test
	@DisplayName("markCompleted: IN_PROGRESS가 아닌 상태에서 호출하면 IllegalStateException을 던진다")
	void markCompleted_throws_whenNotInProgress() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> progress.markCompleted(10, 10))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("markFailed: IN_PROGRESS가 아닌 상태에서 호출하면 IllegalStateException을 던진다")
	void markFailed_throws_whenNotInProgress() {
		BulkImportProgress progress = BulkImportProgress.of("서울특별시", "강남구", "역삼동");

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> progress.markFailed("err"))
			.isInstanceOf(IllegalStateException.class);
	}
}
