package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse;
import sweetgirl.croissant.admin.dto.response.AdminS3ReconcileResponse.Breakdown;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.S3ObjectSummary;
import sweetgirl.croissant.external.aws.exception.S3ErrorCode;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.repository.S3KeyReferenceJdbcRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;

@ExtendWith(MockitoExtension.class)
class AdminS3ReconcileServiceTest {

	private static final Instant OLD = Instant.now().minus(Duration.ofDays(60));
	private static final Instant RECENT = Instant.now().minus(Duration.ofDays(3));

	@Mock
	S3Service s3Service;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	S3KeyReferenceJdbcRepository s3KeyReferenceJdbcRepository;

	@InjectMocks
	AdminS3ReconcileService adminS3ReconcileService;

	/** 접두사 → 그 아래 객체. 테스트가 채워 두면 목록 조회가 그대로 돌려준다. */
	private final Map<String, List<S3ObjectSummary>> bucket = new HashMap<>();

	@BeforeEach
	void setUp() {
		lenient().doAnswer(invocation -> {
			Consumer<S3ObjectSummary> consumer = invocation.getArgument(1);
			bucket.getOrDefault(invocation.<String>getArgument(0), List.of()).forEach(consumer);
			return null;
		}).when(s3Service).forEachObject(anyString(), any());
	}

	private void putObject(String key, long size, Instant lastModified) {
		String prefix = key.startsWith("resized/")
			? key.substring(0, key.indexOf('/', key.indexOf('/', "resized/".length()) + 1) + 1)
			: key.substring(0, key.indexOf('/') + 1);
		bucket.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(new S3ObjectSummary(key, size, lastModified));
	}

	private List<String> deletedKeys() {
		@SuppressWarnings("unchecked") ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
		then(s3Service).should().deleteObjects(captor.capture());
		return captor.getValue();
	}

	@Nested
	@DisplayName("주인 없는 파일 판정")
	class Orphans {

		@Test
		@DisplayName("DB 가 모르는 원본만 주인 없는 것으로 세고 용량을 합한다")
		void countsOnlyUnknownOriginals() {
			given(storeImageFileRepository.findAllAwsKeys()).willReturn(List.of("stores/1/a", "stores/1/b"));
			given(menuImageFileRepository.findAllAwsKeys()).willReturn(List.of("menus/1/a"));
			given(s3KeyReferenceJdbcRepository.findBreadRecordPhotoKeys())
				.willReturn(List.of("bread-records/members/1/images/a"));
			putObject("stores/1/a", 100, OLD);
			putObject("stores/1/b", 100, OLD);
			putObject("stores/9/orphan", 700, OLD);
			putObject("menus/1/a", 100, OLD);
			putObject("menus/9/orphan", 300, OLD);
			putObject("bread-records/members/1/images/a", 100, OLD);
			putObject("bread-records/members/9/images/orphan", 50, OLD);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true);

			assertThat(response.scopes()).containsExactly("stores", "menus", "bread-records");
			assertThat(response.s3KeyCount()).isEqualTo(7);
			assertThat(response.dbKeyCount()).isEqualTo(4);
			assertThat(response.orphanCount()).isEqualTo(3);
			assertThat(response.orphanBytes()).isEqualTo(1050);
			assertThat(response.deletableCount()).isEqualTo(3);
			assertThat(response.deletableBytes()).isEqualTo(1050);
			assertThat(response.orphanKeys()).containsExactly(
				"stores/9/orphan", "menus/9/orphan", "bread-records/members/9/images/orphan");
			assertThat(response.breakdown()).contains(
				new Breakdown("stores", "ORIGINAL", 1, 700, 1, 700),
				new Breakdown("menus", "ORIGINAL", 1, 300, 1, 300),
				new Breakdown("bread-records", "ORIGINAL", 1, 50, 1, 50));
		}

		@Test
		@DisplayName("원본이 DB 에 없으면 그 크기 조정본도 주인 없는 것으로 본다")
		void resizedFollowsUnknownOriginal() {
			putObject("resized/320/menus/9/gone", 10, OLD);
			putObject("resized/640/menus/9/gone", 20, OLD);
			putObject("resized/1024/menus/9/gone", 30, OLD);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, "menus", 7, 10);

			assertThat(response.resizedKeyCount()).isEqualTo(3);
			assertThat(response.orphanCount()).isEqualTo(3);
			assertThat(response.breakdown()).containsExactly(new Breakdown("menus", "RESIZED", 3, 60, 3, 60));
			assertThat(deletedKeys()).containsExactlyInAnyOrder(
				"resized/320/menus/9/gone", "resized/640/menus/9/gone", "resized/1024/menus/9/gone");
		}

		@Test
		@DisplayName("원본을 DB 가 알면 크기 조정본이 일부만 있어도, 원본이 S3 에 없어도 지우지 않는다")
		void keepsResizedOfKnownOriginal() {
			given(storeImageFileRepository.findAllAwsKeys())
				.willReturn(List.of("stores/1/partial", "stores/1/original-lost"));
			putObject("stores/1/partial", 100, OLD);
			putObject("resized/320/stores/1/partial", 10, OLD);
			putObject("resized/640/stores/1/original-lost", 20, OLD);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, "stores", 7, 10);

			assertThat(response.orphanCount()).isZero();
			assertThat(response.missingKeys()).containsExactly("stores/1/original-lost");
			then(s3Service).should(never()).deleteObjects(anyList());
		}

		@Test
		@DisplayName("가게·메뉴·빵 기록과 그 크기 조정본 말고 다른 폴더는 읽지 않는다")
		void listsOnlyTargetPrefixes() {
			adminS3ReconcileService.reconcile(true);

			ArgumentCaptor<String> prefixes = ArgumentCaptor.forClass(String.class);
			then(s3Service).should(times(12)).forEachObject(prefixes.capture(), any());
			assertThat(prefixes.getAllValues()).containsExactlyInAnyOrder(
				"stores/", "resized/320/stores/", "resized/640/stores/", "resized/1024/stores/",
				"menus/", "resized/320/menus/", "resized/640/menus/", "resized/1024/menus/",
				"bread-records/", "resized/320/bread-records/", "resized/640/bread-records/",
				"resized/1024/bread-records/");
		}

		@Test
		@DisplayName("표본은 100개까지만 싣고 개수는 전부 센다")
		void samplesFirstHundred() {
			for (int index = 0; index < 150; index++) {
				putObject("stores/9/orphan-" + index, 1, OLD);
			}

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true, "stores", 7, 1000);

			assertThat(response.orphanCount()).isEqualTo(150);
			assertThat(response.orphanKeys()).hasSize(100);
		}
	}

	@Nested
	@DisplayName("삭제 안전장치")
	class Safeguards {

		@Test
		@DisplayName("dry-run 은 주인 없는 파일이 있어도 삭제·숨김을 한 번도 부르지 않는다")
		void dryRunChangesNothing() {
			given(storeImageFileRepository.findAllAwsKeys()).willReturn(List.of("stores/1/missing"));
			putObject("stores/9/orphan", 700, OLD);
			putObject("resized/320/stores/9/orphan", 70, OLD);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true);

			assertThat(response.dryRun()).isTrue();
			assertThat(response.deletableCount()).isEqualTo(2);
			assertThat(response.deletedCount()).isZero();
			assertThat(response.missingCount()).isEqualTo(1);
			assertThat(response.failedMarkedCount()).isZero();
			then(s3Service).should(never()).deleteObjects(anyList());
			then(storeImageFileRepository).should(never()).updateStatusByAwsKeyInAndStatus(anyList(), any(), any());
			then(menuImageFileRepository).should(never()).updateStatusByAwsKeyInAndStatus(anyList(), any(), any());
		}

		@Test
		@DisplayName("최근 N일 안에 생긴 파일은 주인이 없어도 지우지 않는다")
		void skipsRecentObjects() {
			putObject("stores/9/old", 700, OLD);
			putObject("stores/9/just-uploaded", 500, RECENT);
			putObject("resized/320/stores/9/just-uploaded", 50, RECENT);
			given(s3Service.deleteObjects(anyList())).willReturn(1);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, "stores", 7, 10);

			assertThat(response.orphanCount()).isEqualTo(3);
			assertThat(response.recentSkippedCount()).isEqualTo(2);
			assertThat(response.deletableCount()).isEqualTo(1);
			assertThat(response.deletableBytes()).isEqualTo(700);
			assertThat(response.deletedCount()).isEqualTo(1);
			assertThat(deletedKeys()).containsExactly("stores/9/old");
		}

		@Test
		@DisplayName("유예 일수를 줄이면 그만큼 최근 파일까지 삭제 대상이 된다")
		void honorsMinAgeDays() {
			putObject("stores/9/three-days-old", 500, RECENT);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true, "stores", 1, 10);

			assertThat(response.minAgeDays()).isEqualTo(1);
			assertThat(response.recentSkippedCount()).isZero();
			assertThat(response.deletableCount()).isEqualTo(1);
		}

		@Test
		@DisplayName("삭제 대상이 상한을 넘으면 하나도 지우지 않고 수치만 돌려준다")
		void abortsWhenOverLimit() {
			putObject("stores/9/a", 1, OLD);
			putObject("stores/9/b", 1, OLD);
			putObject("stores/9/c", 1, OLD);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, "stores", 7, 2);

			assertThat(response.deleteAborted()).isTrue();
			assertThat(response.deletableCount()).isEqualTo(3);
			assertThat(response.deletedCount()).isZero();
			assertThat(response.maxDelete()).isEqualTo(2);
			then(s3Service).should(never()).deleteObjects(anyList());
		}

		@Test
		@DisplayName("삭제 대상이 상한과 같으면 지운다")
		void deletesAtLimit() {
			putObject("stores/9/a", 1, OLD);
			putObject("stores/9/b", 1, OLD);
			given(s3Service.deleteObjects(anyList())).willReturn(2);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, "stores", 7, 2);

			assertThat(response.deleteAborted()).isFalse();
			assertThat(response.deletedCount()).isEqualTo(2);
			assertThat(deletedKeys()).containsExactly("stores/9/a", "stores/9/b");
		}

		@Test
		@DisplayName("다른 테이블이 가리키는 파일은 원본도 크기 조정본도 지우지 않는다")
		void protectsKeysReferencedElsewhere() {
			given(s3KeyReferenceJdbcRepository.findProtectedKeys()).willReturn(List.of(
				"stores/3/course-cover", "resized/640/bread-records/members/5/images/report",
				"menus/8/pending"));
			putObject("stores/3/course-cover", 100, OLD);
			putObject("resized/320/stores/3/course-cover", 10, OLD);
			putObject("menus/8/pending", 100, OLD);
			putObject("bread-records/members/5/images/report", 100, OLD);
			putObject("resized/640/bread-records/members/5/images/report", 10, OLD);
			putObject("stores/9/orphan", 700, OLD);
			given(s3Service.deleteObjects(anyList())).willReturn(1);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, null, 7, 10);

			assertThat(response.protectedCount()).isEqualTo(5);
			assertThat(response.orphanCount()).isEqualTo(1);
			assertThat(deletedKeys()).containsExactly("stores/9/orphan");
		}

		@Test
		@DisplayName("참조 조회가 실패하면 S3 를 읽기도 전에 끝난다")
		void failsClosedWhenReferencesUnreadable() {
			given(s3KeyReferenceJdbcRepository.findProtectedKeys())
				.willThrow(new IllegalStateException("relation does not exist"));

			assertThatThrownBy(() -> adminS3ReconcileService.reconcile(false, null, 7, 10))
				.isInstanceOf(IllegalStateException.class);

			then(s3Service).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("S3 에 없는 DB 이미지")
	class Missing {

		@Test
		@DisplayName("가게·메뉴는 FAILED 로 숨기고 빵 기록은 세기만 한다")
		void marksStoreAndMenuOnly() {
			given(storeImageFileRepository.findAllAwsKeys()).willReturn(List.of("stores/3/missing"));
			given(menuImageFileRepository.findAllAwsKeys()).willReturn(List.of("menus/4/missing"));
			given(s3KeyReferenceJdbcRepository.findBreadRecordPhotoKeys())
				.willReturn(List.of("bread-records/members/1/images/missing"));
			given(storeImageFileRepository.updateStatusByAwsKeyInAndStatus(anyList(), any(), any())).willReturn(1);
			given(menuImageFileRepository.updateStatusByAwsKeyInAndStatus(anyList(), any(), any())).willReturn(1);

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, null, 7, 10);

			assertThat(response.missingCount()).isEqualTo(3);
			assertThat(response.failedMarkedCount()).isEqualTo(2);
			then(storeImageFileRepository).should().updateStatusByAwsKeyInAndStatus(
				eq(List.of("stores/3/missing")), eq(ImageStatus.DONE), eq(ImageStatus.FAILED));
			then(menuImageFileRepository).should().updateStatusByAwsKeyInAndStatus(
				eq(List.of("menus/4/missing")), eq(ImageStatus.DONE), eq(ImageStatus.FAILED));
			then(s3Service).should(never()).deleteObjects(anyList());
		}

		@Test
		@DisplayName("숨길 대상이 상한을 넘으면 하나도 숨기지 않는다")
		void abortsMarkingWhenOverLimit() {
			given(storeImageFileRepository.findAllAwsKeys())
				.willReturn(List.of("stores/1/a", "stores/1/b", "stores/1/c"));

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(false, "stores", 7, 2);

			assertThat(response.failedMarkAborted()).isTrue();
			assertThat(response.missingCount()).isEqualTo(3);
			assertThat(response.failedMarkedCount()).isZero();
			then(storeImageFileRepository).should(never()).updateStatusByAwsKeyInAndStatus(anyList(), any(), any());
		}

		@Test
		@DisplayName("다른 폴더의 키를 든 행은 없는 이미지로 세지 않는다")
		void ignoresForeignPrefixRows() {
			given(storeImageFileRepository.findAllAwsKeys()).willReturn(List.of("reports/stores/1/members/2/x"));

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true, "stores", 7, 10);

			assertThat(response.dbKeyCount()).isZero();
			assertThat(response.missingCount()).isZero();
		}
	}

	@Nested
	@DisplayName("실행 제어")
	class Execution {

		@Test
		@DisplayName("폴더를 지정하면 그 폴더만 읽는다")
		void limitsToScope() {
			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true, "bread-records", 7, 10);

			assertThat(response.scopes()).containsExactly("bread-records");
			then(storeImageFileRepository).should(never()).findAllAwsKeys();
			then(menuImageFileRepository).should(never()).findAllAwsKeys();
			then(s3Service).should(never()).forEachObject(eq("stores/"), any());
		}

		@Test
		@DisplayName("대상이 아닌 폴더를 지정하면 거절한다")
		void rejectsUnknownScope() {
			assertThatThrownBy(() -> adminS3ReconcileService.reconcile(false, "reviews", 7, 10))
				.isInstanceOf(S3StorageException.class)
				.hasMessage(S3ErrorCode.RECONCILE_INVALID_OPTION.getMessage());

			then(s3Service).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("유예 일수가 1 미만이면 거절한다")
		void rejectsZeroMinAge() {
			assertThatThrownBy(() -> adminS3ReconcileService.reconcile(false, null, 0, 10))
				.isInstanceOf(S3StorageException.class);

			then(s3Service).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("검사가 도는 중에 또 부르면 거절한다")
		void rejectsConcurrentRun() {
			List<Throwable> nested = new ArrayList<>();
			lenient().doAnswer(invocation -> {
				try {
					adminS3ReconcileService.reconcile(true);
				} catch (S3StorageException exception) {
					nested.add(exception);
				}
				return null;
			}).when(s3Service).forEachObject(eq("stores/"), any());

			adminS3ReconcileService.reconcile(true);

			assertThat(nested).hasSize(1);
			assertThat(nested.get(0)).hasMessage(S3ErrorCode.RECONCILE_ALREADY_RUNNING.getMessage());
		}

		@Test
		@DisplayName("끝난 검사 결과를 다시 읽을 수 있다")
		void keepsLatestResult() {
			assertThat(adminS3ReconcileService.findLatestResult()).isEmpty();

			AdminS3ReconcileResponse response = adminS3ReconcileService.reconcile(true);

			assertThat(adminS3ReconcileService.findLatestResult()).contains(response);
		}
	}
}
