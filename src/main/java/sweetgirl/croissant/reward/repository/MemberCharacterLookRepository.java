package sweetgirl.croissant.reward.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.reward.entity.MemberCharacterLook;
import sweetgirl.croissant.reward.entity.MemberCharacterLookId;

public interface MemberCharacterLookRepository extends JpaRepository<MemberCharacterLook, MemberCharacterLookId> {

	List<MemberCharacterLook> findAllByMemberId(Long memberId);
}
