package sweetgirl.croissant.course.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.course.entity.CourseMemberInvite;
import sweetgirl.croissant.course.enums.CourseMemberInviteStatus;

public interface CourseMemberInviteRepository extends JpaRepository<CourseMemberInvite, Long> {

	boolean existsByCourseIdAndInviteeIdAndStatus(Long courseId, Long inviteeId, CourseMemberInviteStatus status);

	Optional<CourseMemberInvite> findByCourseIdAndInviteeIdAndStatus(Long courseId, Long inviteeId,
			CourseMemberInviteStatus status);
}
