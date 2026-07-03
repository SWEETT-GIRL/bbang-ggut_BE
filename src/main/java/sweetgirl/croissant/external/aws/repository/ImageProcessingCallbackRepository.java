package sweetgirl.croissant.external.aws.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.external.aws.entity.ImageProcessingCallback;

public interface ImageProcessingCallbackRepository extends JpaRepository<ImageProcessingCallback, Long> {

	Optional<ImageProcessingCallback> findByAwsKey(String awsKey);
}
