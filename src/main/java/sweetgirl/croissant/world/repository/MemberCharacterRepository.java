package sweetgirl.croissant.world.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;

public interface MemberCharacterRepository
		extends JpaRepository<MemberCharacter, MemberCharacterId> {

	List<MemberCharacter> findAllByIdMemberId(Long memberId);
}
