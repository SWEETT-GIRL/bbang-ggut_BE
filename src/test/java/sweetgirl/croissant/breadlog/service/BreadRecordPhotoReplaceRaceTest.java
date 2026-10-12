package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPhotoRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.repository.ImageProcessingCallbackRepository;
import sweetgirl.croissant.external.aws.service.ImageCallbackService;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.entity.Member;

/**
 * 수정이 옛 사진을 읽은 뒤, 지우기 전에 처리 결과 콜백이 도착하는 순서를 실제 DB 로 재현한다.
 *
 * 수정은 사진을 전부 지우고 같은 키로 다시 만들면서 읽어 둔 옛 상태를 새 행에 옮긴다. 그 사이 콜백이
 * 옛 행을 DONE 으로 바꾸고 보류 콜백을 지우면, 새 행은 낡은 PENDING 을 물려받고 집어갈 콜백도 없었다.
 * 콜백은 다른 스레드(다른 트랜잭션)로 넣는다 — 수정이 사진을 잠그면 콜백은 수정이 끝날 때까지 기다린다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:bread-record-photo-replace-race-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BreadRecordPhotoReplaceRaceTest {

	@MockitoBean
	private S3Service s3Service;

	// 코스 진행도는 H2 에 없는 표를 읽는다. 이 테스트가 보려는 것과 무관하다.
	@MockitoBean
	private CourseProgressService courseProgressService;

	@MockitoSpyBean
	private BreadRecordTagRepository tagRepository;

	@MockitoSpyBean
	private ImageProcessingCallbackRepository imageProcessingCallbackRepository;

	@Autowired
	private BreadRecordCommandService breadRecordCommandService;

	@Autowired
	private ImageCallbackService imageCallbackService;

	@Autowired
	private BreadRecordPhotoRepository photoRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private final ExecutorService callbackThread = Executors.newSingleThreadExecutor();
	private final AtomicReference<Future<?>> callback = new AtomicReference<>();

	private Long memberId;
	private Long recordId;
	private String awsKey;

	@BeforeEach
	void setUp() {
		transactionTemplate.executeWithoutResult(status -> {
			Member member = new Member("빵순이");
			entityManager.persist(member);
			memberId = member.getId();
			awsKey = S3PathGenerator.getBreadRecordImagePath(memberId) + "/race";
			BreadRecord record = BreadRecord.builder()
				.member(member)
				.storeName("빵집")
				.lovedBreads(List.of())
				.eatenAt(LocalDateTime.of(2026, 10, 12, 12, 0))
				.isPublic(false)
				.build();
			entityManager.persist(record);
			recordId = record.getId();
			entityManager.persist(BreadRecordPhoto.builder()
				.record(record)
				.name("race.jpg")
				.mimeType(MimeType.IMAGE_JPEG)
				.awsKey(awsKey)
				.sortOrder(0)
				.build());
		});
		// H2 는 ON CONFLICT DO UPDATE 를 모른다 — 같은 트랜잭션(같은 커넥션)에서 도는 MERGE 로 바꿔 끼운다.
		willAnswer(invocation -> jdbcTemplate.update("""
				MERGE INTO image_processing_callback (aws_key, status, fail_reason, created_at, updated_at)
				KEY (aws_key) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", invocation.getArguments()))
			.given(imageProcessingCallbackRepository).upsert(any(), any(), any());
	}

	@AfterEach
	void tearDown() {
		callbackThread.shutdownNow();
		jdbcTemplate.update("DELETE FROM image_processing_callback");
		jdbcTemplate.update("DELETE FROM bread_record_photo");
		jdbcTemplate.update("DELETE FROM bread_record");
		jdbcTemplate.update("DELETE FROM member");
	}

	@Test
	@DisplayName("수정이 옛 사진을 읽은 뒤 콜백이 도착해도 다시 만든 사진에 처리 결과가 남는다")
	void replace_callbackArrivesAfterOldPhotosRead_newPhotoKeepsResult() throws Exception {
		callbackArrivesBeforeOldPhotosAreDeleted();

		breadRecordCommandService.replace(recordId, memberId, sameKeyRequest());
		callback.get().get(10, TimeUnit.SECONDS);

		List<BreadRecordPhoto> photos = photoRepository.findAllByAwsKey(awsKey);
		assertThat(photos).hasSize(1);
		assertThat(photos.get(0).getProcessingStatus()).isEqualTo(ImageStatus.DONE);
		assertThat(photos.get(0).getResizedKey320()).isEqualTo("resized/320/" + awsKey);
		assertThat(photos.get(0).getResizedKey640()).isEqualTo("resized/640/" + awsKey);
		assertThat(photos.get(0).getResizedKey1024()).isEqualTo("resized/1024/" + awsKey);
		assertThat(imageProcessingCallbackRepository.findByAwsKey(awsKey)).isEmpty();
	}

	/**
	 * 수정이 옛 사진을 읽고 나서 사진을 지우기 직전(태그 삭제 시점)에 콜백을 다른 스레드로 넣는다.
	 * 콜백이 바로 끝나면 그 뒤에, 수정에 막혀 기다리면 잠깐만 지켜본 뒤에 수정을 이어 간다.
	 */
	private void callbackArrivesBeforeOldPhotosAreDeleted() {
		Answer<?> realDelete = Mockito.mockingDetails(tagRepository).getMockCreationSettings().getDefaultAnswer();
		ImageStatusUpdateRequest done = new ImageStatusUpdateRequest();
		ReflectionTestUtils.setField(done, "awsKey", awsKey);
		ReflectionTestUtils.setField(done, "status", ImageStatus.DONE);
		willAnswer(invocation -> {
			callback.set(callbackThread.submit(() -> imageCallbackService.updateImageStatus(done)));
			try {
				callback.get().get(500, TimeUnit.MILLISECONDS);
			} catch (TimeoutException waitingForReplace) {
				// 수정이 잠근 사진을 기다리는 중 — 수정이 커밋해야 풀린다
			}
			return realDelete.answer(invocation);
		}).given(tagRepository).deleteAllByRecordId(recordId);
	}

	private BreadRecordUpsertRequest sameKeyRequest() {
		return new BreadRecordUpsertRequest(
			null,
			"빵집",
			null,
			"메모만 고침",
			null,
			null,
			null,
			LocalDateTime.of(2026, 10, 12, 12, 0),
			List.of(new BreadRecordPhotoRequest("race.jpg", "image/jpeg", awsKey, 0, null, null)),
			List.of(),
			null);
	}
}
