package sweetgirl.croissant.pat.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.member.entity.Member;

/**
 * from 이 to 에게 톡을 쳤다 — 사람마다 하루 1번(KST). 쓰기는 {@code MemberPatRepository.insertIfAbsent} 가 한다.
 * 이 엔티티는 "누가 톡 쳤나" 목록 읽기용이다.
 */
@Entity
@Table(name = "member_pat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPat {

	@EmbeddedId
	private MemberPatId id;

	@MapsId("fromMemberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "from_member_id", nullable = false)
	private Member fromMember;

	@MapsId("toMemberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "to_member_id", nullable = false)
	private Member toMember;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;
}
