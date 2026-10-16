package sweetgirl.croissant.global.alert;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 5xx 로 끝난 요청을 운영 채널에 알린다. {@code GlobalExceptionHandler} 가 응답을 만들기 직전에 부른다.
 *
 * <p>알림이 꺼진 환경(테스트·로컬·스테이징)에서는 {@link #NOOP} 이 대신한다.
 */
public interface ServerErrorAlerter {

	ServerErrorAlerter NOOP = (request, status, exception) -> false;

	/**
	 * @return 이 오류를 알림이 맡았으면(보냈거나, 같은 오류가 방금 나가서 삼켰으면) true.
	 *     true 면 핸들러가 ERROR 로그에 표식을 달아 슬랙 로그 어펜더가 같은 오류를 또 보내지 않게 한다.
	 */
	boolean alert(HttpServletRequest request, int status, Throwable exception);
}
