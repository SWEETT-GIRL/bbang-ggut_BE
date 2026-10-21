package sweetgirl.croissant.appversion.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.appversion.entity.AppVersionPolicy;
import sweetgirl.croissant.appversion.enums.AppPlatform;

public interface AppVersionPolicyRepository extends JpaRepository<AppVersionPolicy, Long> {

	Optional<AppVersionPolicy> findByPlatform(AppPlatform platform);
}
