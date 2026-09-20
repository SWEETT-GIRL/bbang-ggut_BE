package sweetgirl.croissant.world.entity;

import java.time.LocalDateTime;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 어느 행성에 언제 닿았는가.
 *
 * <p>진행도 판정이 이 표를 함께 보는 이유는 <b>운영 중 임계값을 올려도 이미 도착한 회원을 뒤로
 * 밀지 않기 위해서</b>다. 도착 이력이 곧 "간 적 있다"는 사실이므로 계산값보다 이쪽이 세다.
 *
 * <p>{@code rouletteAt} 은 그 행성의 도착 룰렛을 돌렸으면 찬다. 이중 지급은 이 칸을 건 조건부
 * UPDATE 로 막는다.
 */
@Entity
@Table(name = "member_planet_arrivals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberPlanetArrival {

	@EmbeddedId
	private MemberPlanetArrivalId id;

	private LocalDateTime arrivedAt;

	private LocalDateTime rouletteAt;

	public String planetCode() {
		return id.getPlanetCode();
	}

	public boolean hasSpunRoulette() {
		return rouletteAt != null;
	}
}
