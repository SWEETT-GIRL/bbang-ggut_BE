package sweetgirl.croissant.admin.bulkimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.bulkimport.entity.BulkImportProgress;
import sweetgirl.croissant.admin.bulkimport.loader.DongListLoader;
import sweetgirl.croissant.admin.bulkimport.loader.DongRow;

@ExtendWith(MockitoExtension.class)
class BulkImportOrchestratorTest {

	@Mock
	DongListLoader dongListLoader;
	@Mock
	BulkImportProgressService progressService;
	@Mock
	DongImporter dongImporter;

	@InjectMocks
	BulkImportOrchestrator orchestrator;

	@Test
	@DisplayName("tryStart: 이미 실행 중이면 false를 반환한다")
	void tryStart_returnsFalse_whenAlreadyRunning() {
		assertThat(orchestrator.tryStart()).isTrue();
		assertThat(orchestrator.tryStart()).isFalse();
	}

	@Test
	@DisplayName("runImport: PENDING + FAILED 동을 모두 처리하고 각 동에 importDong을 호출한다")
	void runImport_processesPendingAndFailedDongs() throws Exception {
		given(dongListLoader.load()).willReturn(List.of(
			new DongRow("서울", "강남구", "역삼동"),
			new DongRow("서울", "강남구", "개포동")));
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(progressService.findOrCreate("서울", "강남구", "역삼동")).willReturn(p1);
		given(progressService.findOrCreate("서울", "강남구", "개포동")).willReturn(p2);
		given(progressService.getPendingOrFailed()).willReturn(List.of(p1, p2));
		given(dongImporter.importDong(anyString(), anyString(), anyString()))
			.willReturn(new DongImporter.DongImportResult(5, 5));

		orchestrator.tryStart();
		orchestrator.runImport();

		verify(dongImporter).importDong("서울", "강남구", "역삼동");
		verify(dongImporter).importDong("서울", "강남구", "개포동");
		verify(progressService).markCompleted(p1, 5, 5);
		verify(progressService).markCompleted(p2, 5, 5);
	}

	@Test
	@DisplayName("runImport: importDong 예외 시 해당 동을 FAILED로 마크하고 다음 동 진행한다")
	void runImport_marksFailed_onException() throws Exception {
		given(dongListLoader.load()).willReturn(List.of(
			new DongRow("서울", "강남구", "역삼동"),
			new DongRow("서울", "강남구", "개포동")));
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(progressService.findOrCreate("서울", "강남구", "역삼동")).willReturn(p1);
		given(progressService.findOrCreate("서울", "강남구", "개포동")).willReturn(p2);
		given(progressService.getPendingOrFailed()).willReturn(List.of(p1, p2));
		given(dongImporter.importDong("서울", "강남구", "역삼동"))
			.willThrow(new RuntimeException("Kakao timeout"));
		given(dongImporter.importDong("서울", "강남구", "개포동"))
			.willReturn(new DongImporter.DongImportResult(3, 3));

		orchestrator.tryStart();
		orchestrator.runImport();

		verify(progressService).markFailed(p1, "Kakao timeout");
		verify(progressService).markCompleted(p2, 3, 3);
	}

	@Test
	@DisplayName("requestStop: shouldStop 셋팅 시 다음 동 처리 전에 종료한다")
	void requestStop_haltsLoop() throws Exception {
		given(dongListLoader.load()).willReturn(List.of(
			new DongRow("서울", "강남구", "역삼동"),
			new DongRow("서울", "강남구", "개포동")));
		BulkImportProgress p1 = BulkImportProgress.of("서울", "강남구", "역삼동");
		BulkImportProgress p2 = BulkImportProgress.of("서울", "강남구", "개포동");
		given(progressService.findOrCreate("서울", "강남구", "역삼동")).willReturn(p1);
		given(progressService.findOrCreate("서울", "강남구", "개포동")).willReturn(p2);
		given(progressService.getPendingOrFailed()).willReturn(List.of(p1, p2));
		given(dongImporter.importDong("서울", "강남구", "역삼동"))
			.willAnswer(inv -> {
				orchestrator.requestStop();
				return new DongImporter.DongImportResult(1, 1);
			});

		orchestrator.tryStart();
		orchestrator.runImport();

		verify(dongImporter).importDong("서울", "강남구", "역삼동");
		verify(dongImporter, never()).importDong("서울", "강남구", "개포동");
	}

	@Test
	@DisplayName("recoverStaleOnStartup: 시작 시 IN_PROGRESS를 PENDING으로 되돌린다")
	void recoverStaleOnStartup_resetsInProgressToPending() {
		given(progressService.recoverStaleInProgress()).willReturn(2);

		orchestrator.recoverStaleOnStartup(null);

		verify(progressService).recoverStaleInProgress();
	}
}
