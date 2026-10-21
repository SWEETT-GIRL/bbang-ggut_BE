package sweetgirl.croissant.member.dto.request;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class NotificationTimeRequest {

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

	// 알림 시작 시간 (HH:mm 형식, 예: "09:00")
	@NotBlank
	@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "시간 형식은 HH:mm 이어야 합니다.")
	private String startTime;

	// 알림 종료 시간 (HH:mm 형식, 예: "21:00")
	@NotBlank
	@Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "시간 형식은 HH:mm 이어야 합니다.")
	private String endTime;

	// 문자열 → LocalTime 변환
	public LocalTime toStartTime() {
		return LocalTime.parse(startTime, FORMATTER);
	}

	public LocalTime toEndTime() {
		return LocalTime.parse(endTime, FORMATTER);
	}

	// startTime이 endTime보다 이전인지 검증
	@AssertTrue(message = "시작 시간은 종료 시간보다 이전이어야 합니다.")
	public boolean isStartBeforeEnd() {
		if (startTime == null || endTime == null) {
			return true;
		}
		try {
			return toStartTime().isBefore(toEndTime());
		} catch (Exception e) {
			return true;
		}
	}
}
