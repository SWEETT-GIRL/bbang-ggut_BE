package sweetgirl.croissant.external.aws.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.external.aws.entity.ImageProcessingCallback;

public interface ImageProcessingCallbackRepository extends JpaRepository<ImageProcessingCallback, Long> {

	Optional<ImageProcessingCallback> findByAwsKey(String awsKey);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO image_processing_callback (
				aws_key, status, fail_reason, created_at, updated_at
			) VALUES (
				:awsKey, :status, :failReason, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
			)
			ON CONFLICT (aws_key) DO UPDATE SET
				status = EXCLUDED.status,
				fail_reason = EXCLUDED.fail_reason,
				updated_at = CURRENT_TIMESTAMP
			""", nativeQuery = true)
	void upsert(
			@Param("awsKey") String awsKey,
			@Param("status") String status,
			@Param("failReason") String failReason);
}
