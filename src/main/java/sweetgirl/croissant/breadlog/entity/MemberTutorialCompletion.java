package sweetgirl.croissant.breadlog.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 기록을 삭제해도 남는다. 템플릿 버전 변경이나 재설치로 보상을 다시 지급하지 않는다. */
@Entity
@Table(name = "member_tutorial_completion")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTutorialCompletion {
	@Id
	private Long memberId;
	private Long recordId;
	private String templateCode;
	private LocalDateTime completedAt;

	public MemberTutorialCompletion(Long memberId, Long recordId, String templateCode) {
		this.memberId = memberId;
		this.recordId = recordId;
		this.templateCode = templateCode;
		this.completedAt = LocalDateTime.now();
	}
}
