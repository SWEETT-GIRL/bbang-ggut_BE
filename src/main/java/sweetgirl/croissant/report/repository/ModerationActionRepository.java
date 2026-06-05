package sweetgirl.croissant.report.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.report.entity.ModerationAction;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

}
