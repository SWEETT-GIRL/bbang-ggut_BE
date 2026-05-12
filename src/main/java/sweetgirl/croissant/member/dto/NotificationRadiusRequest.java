package sweetgirl.croissant.member.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NotificationRadiusRequest {

	// 반경 변경 방향: UP(한 단계 증가) 또는 DOWN(한 단계 감소)
	@NotNull
	private Direction direction;

	public enum Direction {
		UP, DOWN
	}

	// UP 방향 여부 판별
	public boolean isUp() {
		return direction == Direction.UP;
	}
}
