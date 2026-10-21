package sweetgirl.croissant.global.logging.filter;

import org.springframework.web.filter.CommonsRequestLoggingFilter;

import jakarta.servlet.http.HttpServletRequest;
import sweetgirl.croissant.global.logging.SensitiveUrlMasker;

/** CommonsRequestLoggingFilter 의 DEBUG 메시지(uri=…;client=…;payload=…)에서 초대·공유 토큰을 가린다. */
public class MaskingRequestLoggingFilter extends CommonsRequestLoggingFilter {

	@Override
	protected String createMessage(HttpServletRequest request, String prefix, String suffix) {
		return SensitiveUrlMasker.maskUrl(super.createMessage(request, prefix, suffix));
	}
}
