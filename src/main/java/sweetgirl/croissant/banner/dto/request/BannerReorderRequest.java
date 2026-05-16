package sweetgirl.croissant.banner.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class BannerReorderRequest {

	@NotEmpty(message = "배너 ID 목록은 필수입니다.")
	private List<Long> bannerIds;
}
