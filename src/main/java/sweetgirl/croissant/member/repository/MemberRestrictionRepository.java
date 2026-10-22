package sweetgirl.croissant.member.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.MemberRestriction;
import sweetgirl.croissant.member.enums.MemberRestrictionType;

public interface MemberRestrictionRepository extends JpaRepository<MemberRestriction, Long> {

	@Query("""
			select r from MemberRestriction r
			where r.member.id = :memberId
				and r.restrictionType in :restrictionTypes
				and r.startsAt <= :now
				and (r.endsAt is null or r.endsAt > :now)
			order by r.startsAt desc, r.id desc
			""")
	List<MemberRestriction> findActiveByMemberIdAndRestrictionTypes(
			@Param("memberId") Long memberId,
			@Param("restrictionTypes") Collection<MemberRestrictionType> restrictionTypes,
			@Param("now") LocalDateTime now);

	/** 탈퇴한 지 cutoff 를 넘긴 사람의 제재 기록을 지운다 — 보관 기한 뒤. */
	@Modifying
	@Query("""
			DELETE FROM MemberRestriction mr
			WHERE mr.member.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
			""")
	int deleteAllOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
