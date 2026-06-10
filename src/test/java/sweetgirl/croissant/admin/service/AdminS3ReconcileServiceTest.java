package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class AdminS3ReconcileServiceTest {

	@Mock
	S3Service s3Service;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;

	@InjectMocks
	AdminS3ReconcileService adminS3ReconcileService;

	@Test
	@DisplayName("reconcile dryRun=true: orphan/missing 목록만 반환하고 변경은 안 함")
	void reconcile_dryRun_listsOnly() {
		given(s3Service.listAllKeys("stores/")).willReturn(List.of("stores/1/a", "stores/1/orphan"));
		given(s3Service.listAllKeys("menus/")).willReturn(List.of("menus/1/b"));
		given(storeImageFileRepository.findAllAwsKeys()).willReturn(List.of("stores/1/a", "stores/1/missing"));
		given(menuImageFileRepository.findAllAwsKeys()).willReturn(List.of("menus/1/b"));

		AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true);

		assertThat(response.dryRun()).isTrue();
		assertThat(response.s3KeyCount()).isEqualTo(3);
		assertThat(response.dbKeyCount()).isEqualTo(3);
		assertThat(response.orphanCount()).isEqualTo(1);
		assertThat(response.deletedCount()).isZero();
		assertThat(response.orphanKeys()).containsExactly("stores/1/orphan");
		assertThat(response.missingCount()).isEqualTo(1);
		assertThat(response.failedMarkedCount()).isZero();
		assertThat(response.missingKeys()).containsExactly("stores/1/missing");
		verify(s3Service, never()).deleteObjects(org.mockito.ArgumentMatchers.anyList());
		verify(storeImageFileRepository, never()).updateStatusByAwsKeyInAndStatus(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("reconcile dryRun=false: orphan 은 삭제하고 missing 은 FAILED 처리")
	void reconcile_actualDeleteAndMarkFailed() {
		given(s3Service.listAllKeys("stores/")).willReturn(List.of("stores/1/orphan-a", "stores/2/orphan-b"));
		given(s3Service.listAllKeys("menus/")).willReturn(List.of());
		given(storeImageFileRepository.findAllAwsKeys()).willReturn(List.of("stores/3/missing-a"));
		given(menuImageFileRepository.findAllAwsKeys()).willReturn(List.of("menus/4/missing-b"));
		given(s3Service.deleteObjects(org.mockito.ArgumentMatchers.anyList())).willReturn(2);
		given(storeImageFileRepository.updateStatusByAwsKeyInAndStatus(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any())).willReturn(1);
		given(menuImageFileRepository.updateStatusByAwsKeyInAndStatus(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any())).willReturn(1);

		AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false);

		assertThat(response.dryRun()).isFalse();
		assertThat(response.orphanCount()).isEqualTo(2);
		assertThat(response.deletedCount()).isEqualTo(2);
		assertThat(response.missingCount()).isEqualTo(2);
		assertThat(response.failedMarkedCount()).isEqualTo(2);

		@SuppressWarnings("unchecked") ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		verify(s3Service).deleteObjects(captor.capture());
		assertThat(captor.getValue()).containsExactlyInAnyOrder("stores/1/orphan-a", "stores/2/orphan-b");
		verify(storeImageFileRepository).updateStatusByAwsKeyInAndStatus(
			org.mockito.ArgumentMatchers.eq(List.of("stores/3/missing-a")),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any());
		verify(menuImageFileRepository).updateStatusByAwsKeyInAndStatus(
			org.mockito.ArgumentMatchers.eq(List.of("menus/4/missing-b")),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("reconcile: orphan/missing 0개면 변경 호출 안 함")
	void reconcile_noOrphan() {
		given(s3Service.listAllKeys("stores/")).willReturn(List.of("stores/1/a"));
		given(s3Service.listAllKeys("menus/")).willReturn(List.of());
		given(storeImageFileRepository.findAllAwsKeys()).willReturn(List.of("stores/1/a"));
		given(menuImageFileRepository.findAllAwsKeys()).willReturn(List.of());

		AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false);

		assertThat(response.orphanCount()).isZero();
		assertThat(response.deletedCount()).isZero();
		assertThat(response.missingCount()).isZero();
		assertThat(response.failedMarkedCount()).isZero();
		verify(s3Service, never()).deleteObjects(org.mockito.ArgumentMatchers.anyList());
		verify(storeImageFileRepository, never()).updateStatusByAwsKeyInAndStatus(
			org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any());
	}
}
