package sweetgirl.croissant.external.aws.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

import java.time.LocalDateTime;
import java.util.List;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.repository.ImageProcessingCallbackRepository;
import sweetgirl.croissant.member.entity.Member;

/**
 * 콜백이 사진 행을 읽은 뒤 그 행이 사라지는 순서를 실제 DB 로 재현한다.
 *
 * 빵 기록 수정은 사진을 전부 지우고 같은 키로 다시 만든다. 콜백이 읽어 둔 행을 엔티티로 고치면
 * 커밋 때 UPDATE 가 0행을 맞아 트랜잭션이 통째로 롤백되고, 방금 저장한 보류 콜백까지 사라졌다.
 * 다른 트랜잭션이 끼어드는 자리를 고정하려고 사진 조회 직후에 삭제를 실행한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:image-callback-stale-photo-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImageCallbackStalePhotoTest {

	private static final String AWS_KEY = "bread-records/members/7/images/stale";

	@MockitoBean
	private S3Service s3Service;

	@MockitoSpyBean
	private BreadRecordPhotoRepository breadRecordPhotoRepository;

	@Autowired
	private ImageCallbackService imageCallbackService;

	@Autowired
	private ImageProcessingCallbackService imageProcessingCallbackService;

	@MockitoSpyBean
	private ImageProcessingCallbackRepository imageProcessingCallbackRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate otherTransaction;

	private Long recordId;

	@BeforeEach
	void setUp() {
		otherTransaction = new TransactionTemplate(transactionManager);
		otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		recordId = otherTransaction.execute(status -> {
			Member member = new Member("빵순이");
			entityManager.persist(member);
			BreadRecord record = BreadRecord.builder()
				.member(member)
				.storeName("빵집")
				.lovedBreads(List.of())
				.eatenAt(LocalDateTime.of(2026, 10, 6, 12, 0))
				.isPublic(false)
				.build();
			entityManager.persist(record);
			entityManager.persist(photo(record));
			return record.getId();
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
		jdbcTemplate.update("DELETE FROM image_processing_callback");
		jdbcTemplate.update("DELETE FROM bread_record_photo");
		jdbcTemplate.update("DELETE FROM bread_record");
		jdbcTemplate.update("DELETE FROM member");
	}

	@Test
	@DisplayName("콜백이 사진을 읽은 뒤 수정이 같은 키로 행을 다시 만들어도 실패하지 않고 새 행에 반영한다")
	void updateImageStatus_photoReplacedAfterRead_appliesToNewRow() {
		afterCallbackReadsPhotos(() -> {
			breadRecordPhotoRepository.deleteAllIncludingPendingByRecordId(recordId);
			entityManager.persist(photo(entityManager.getReference(BreadRecord.class, recordId)));
		});

		assertThatCode(() -> imageCallbackService.updateImageStatus(request(ImageStatus.DONE)))
			.doesNotThrowAnyException();

		List<BreadRecordPhoto> photos = breadRecordPhotoRepository.findAllByAwsKey(AWS_KEY);
		assertThat(photos).hasSize(1);
		assertThat(photos.get(0).getProcessingStatus()).isEqualTo(ImageStatus.DONE);
		assertThat(photos.get(0).getResizedKey320()).isEqualTo("resized/320/" + AWS_KEY);
		assertThat(photos.get(0).getResizedKey640()).isEqualTo("resized/640/" + AWS_KEY);
		assertThat(photos.get(0).getResizedKey1024()).isEqualTo("resized/1024/" + AWS_KEY);
		assertThat(imageProcessingCallbackRepository.findByAwsKey(AWS_KEY)).isEmpty();
	}

	@Test
	@DisplayName("콜백이 사진을 읽은 뒤 행이 사라지면 콜백은 보류로 남아 나중에 만든 행이 집어간다")
	void updateImageStatus_photoDeletedAfterRead_keepsPendingCallbackForNextRow() {
		afterCallbackReadsPhotos(() -> breadRecordPhotoRepository.deleteAllIncludingPendingByRecordId(recordId));

		assertThatCode(() -> imageCallbackService.updateImageStatus(request(ImageStatus.DONE)))
			.doesNotThrowAnyException();

		assertThat(breadRecordPhotoRepository.findAllByAwsKey(AWS_KEY)).isEmpty();
		assertThat(imageProcessingCallbackRepository.findByAwsKey(AWS_KEY)).isPresent();

		// 빵 기록 생성·수정이 사진 행을 만든 직후 보류 콜백을 집어가는 경로
		otherTransaction.executeWithoutResult(status -> {
			BreadRecordPhoto recreated = photo(entityManager.getReference(BreadRecord.class, recordId));
			breadRecordPhotoRepository.saveAll(List.of(recreated));
			imageProcessingCallbackService.applyPendingCallbacks(List.of(recreated));
		});

		List<BreadRecordPhoto> photos = breadRecordPhotoRepository.findAllByAwsKey(AWS_KEY);
		assertThat(photos).hasSize(1);
		assertThat(photos.get(0).getProcessingStatus()).isEqualTo(ImageStatus.DONE);
		assertThat(imageProcessingCallbackRepository.findByAwsKey(AWS_KEY)).isEmpty();
	}

	/** 콜백 트랜잭션이 사진을 읽은 직후, 다른 트랜잭션이 끼어들어 커밋까지 끝낸 상황을 만든다. 한 번만 끼어든다. */
	private void afterCallbackReadsPhotos(Runnable concurrentChange) {
		Answer<?> realQuery = Mockito.mockingDetails(breadRecordPhotoRepository)
			.getMockCreationSettings()
			.getDefaultAnswer();
		willAnswer(invocation -> {
			Object photosReadByCallback = realQuery.answer(invocation);
			otherTransaction.executeWithoutResult(status -> concurrentChange.run());
			return photosReadByCallback;
		}).willAnswer(realQuery).given(breadRecordPhotoRepository).findAllByAwsKey(AWS_KEY);
	}

	private BreadRecordPhoto photo(BreadRecord record) {
		return BreadRecordPhoto.builder()
			.record(record)
			.name("stale.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey(AWS_KEY)
			.sortOrder(0)
			.build();
	}

	private ImageStatusUpdateRequest request(ImageStatus status) {
		ImageStatusUpdateRequest request = new ImageStatusUpdateRequest();
		ReflectionTestUtils.setField(request, "awsKey", AWS_KEY);
		ReflectionTestUtils.setField(request, "status", status);
		return request;
	}
}
