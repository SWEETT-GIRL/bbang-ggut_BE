package sweetgirl.croissant.notification.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.inquiry.entity.Inquiry;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.notification.code.AdminNotificationMessage;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;
import sweetgirl.croissant.store.entity.Store;

@Component
@RequiredArgsConstructor
public class AdminReportNotificationPublisher {

	private static final int DETAIL_PREVIEW_LENGTH = 500;

	private final ApplicationEventPublisher eventPublisher;

	/** 어드민 웹 주소. 비어 있으면 링크 없이 보낸다. */
	@Value("${admin.web-base-url:}")
	private String adminWebBaseUrl;

	public void publishStoreContributionReportCreated(StoreContributionReport report) {
		ContributionReport contributionReport = report.getContributionReport();
		Store store = report.getStore();

		List<OperationalNotificationEvent.Field> fields = new ArrayList<>();
		addField(fields, "제보 ID", contributionReport.getId(), true);
		addField(fields, "상세 ID", report.getId(), true);
		addField(fields, "요청", report.getRequestType(), true);
		addField(fields, "제보자 ID", memberId(contributionReport.getMember()), true);
		addField(fields, "가게", storeLabel(store, report.getName()), false);
		addField(fields, "주소", firstNotBlank(report.getRoadAddress(), report.getAddress()), false);
		addField(fields, "사유", report.getReason(), false);

		boolean isAdd = contributionReport.getReportType() == ContributionReportType.ADD;
		String subject = isAdd
			? AdminNotificationMessage.STORE_ADD_SUBJECT
			: AdminNotificationMessage.STORE_UPDATE_SUBJECT;
		publish(
			Channel.REPORT,
			AdminNotificationMessage.title(AdminNotificationMessage.STORE_EMOJI, subject),
			AdminNotificationMessage.description(subject),
			AdminNotificationMessage.REPORT_COLOR,
			fields);
	}

	public void publishMenuContributionReportCreated(MenuContributionReport report) {
		ContributionReport contributionReport = report.getContributionReport();
		Menu menu = report.getMenu();

		List<OperationalNotificationEvent.Field> fields = new ArrayList<>();
		addField(fields, "제보 ID", contributionReport.getId(), true);
		addField(fields, "상세 ID", report.getId(), true);
		addField(fields, "요청", report.getRequestType(), true);
		addField(fields, "제보자 ID", memberId(contributionReport.getMember()), true);
		addField(fields, "가게", storeLabel(report.getStore(), null), false);
		addField(fields, "메뉴", menuLabel(menu, report.getName()), false);
		addField(fields, "가격", report.getPrice() != null ? report.getPrice() + "원" : null, true);
		addField(fields, "내용", firstNotBlank(report.getDescription(), report.getMessage()), false);

		boolean isAdd = contributionReport.getReportType() == ContributionReportType.ADD;
		String subject = isAdd
			? AdminNotificationMessage.MENU_ADD_SUBJECT
			: AdminNotificationMessage.MENU_UPDATE_SUBJECT;
		publish(
			Channel.REPORT,
			AdminNotificationMessage.title(AdminNotificationMessage.MENU_EMOJI, subject),
			AdminNotificationMessage.description(subject),
			AdminNotificationMessage.REPORT_COLOR,
			fields);
	}

	public void publishAbuseReportCreated(AbuseReport report) {
		List<OperationalNotificationEvent.Field> fields = new ArrayList<>();
		addField(fields, "신고 ID", report.getId(), true);
		addField(fields, "대상", report.getTargetType() + " #" + report.getTargetId(), true);
		addField(fields, "사유", report.getReasonType(), true);
		addField(fields, "신고자 ID", memberId(report.getReporter()), true);
		addField(fields, "피신고자 ID", memberId(report.getReportedMember()), true);
		addField(fields, "상세", report.getReasonDetail(), false);
		addField(fields, "어드민", adminReportLink(report.getId()), false);

		String subject = abuseSubjectOf(report.getTargetType());
		publish(
			Channel.ABUSE,
			AdminNotificationMessage.title(AdminNotificationMessage.ABUSE_EMOJI, subject),
			AdminNotificationMessage.description(subject),
			AdminNotificationMessage.ABUSE_COLOR,
			fields);
	}

	/** 24시간 안에 처리 안 된 신고를 다시 알린다 — 애플 1.2 "24시간 안 조치"의 안전장치. */
	public void publishAbuseReportsOverdue(List<AbuseReport> reports) {
		if (reports.isEmpty()) {
			return;
		}
		List<OperationalNotificationEvent.Field> fields = new ArrayList<>();
		addField(fields, "건수", reports.size(), true);
		String lines = reports.stream()
			.map(report -> "#" + report.getId() + " " + abuseSubjectOf(report.getTargetType())
				+ " · " + report.getReasonType() + " · " + adminReportLink(report.getId()))
			.collect(Collectors.joining("\n"));
		addField(fields, "목록", lines, false);
		String subject = AdminNotificationMessage.ABUSE_OVERDUE_SUBJECT;
		publish(
			Channel.ABUSE,
			AdminNotificationMessage.title(AdminNotificationMessage.ABUSE_EMOJI, subject),
			subject + " — 지금 처리해 주세요.",
			AdminNotificationMessage.ABUSE_COLOR,
			fields);
	}

	static String abuseSubjectOf(AbuseReportTargetType targetType) {
		return switch (targetType) {
			case REVIEW -> AdminNotificationMessage.REVIEW_ABUSE_SUBJECT;
			case COURSE -> AdminNotificationMessage.COURSE_ABUSE_SUBJECT;
			case RECORD -> AdminNotificationMessage.RECORD_ABUSE_SUBJECT;
			case COMMENT -> AdminNotificationMessage.COMMENT_ABUSE_SUBJECT;
			case MEMBER_PROFILE -> AdminNotificationMessage.PROFILE_ABUSE_SUBJECT;
		};
	}

	private String adminReportLink(Long reportId) {
		if (adminWebBaseUrl == null || adminWebBaseUrl.isBlank()) {
			return null;
		}
		return adminWebBaseUrl.replaceAll("/+$", "") + "/reports/" + reportId + "?kind=ABUSE_REPORT";
	}

	public void publishInquiryCreated(Inquiry inquiry) {
		List<OperationalNotificationEvent.Field> fields = new ArrayList<>();
		addField(fields, "문의 ID", inquiry.getId(), true);
		addField(fields, "카테고리", inquiry.getCategory(), true);
		addField(fields, "회원 ID", memberId(inquiry.getMember()), true);
		addField(fields, "제목", inquiry.getTitle(), false);
		addField(fields, "내용", inquiry.getContent(), false);

		String subject = AdminNotificationMessage.INQUIRY_SUBJECT;
		publish(
			Channel.REPORT,
			AdminNotificationMessage.title(AdminNotificationMessage.INQUIRY_EMOJI, subject),
			AdminNotificationMessage.description(subject),
			AdminNotificationMessage.INQUIRY_COLOR,
			fields);
	}

	/**
	 * 사용자가 편지함으로 보낸 편지. 어드민 답장·가입 인사·일괄 발신은 여기로 오지 않는다.
	 *
	 * <p>필드를 지금 문자열로 굳혀 둔다 — 호출 직후 벌크 UPDATE가 영속성 컨텍스트를 비워서,
	 * 커밋 뒤 리스너에서 엔티티를 만지면 준영속 프록시를 건드리게 된다.
	 */
	public void publishLetterReceived(LetterMessage message) {
		LetterThread thread = message.getThread();
		Member member = thread.getMember();

		List<OperationalNotificationEvent.Field> fields = new ArrayList<>();
		addField(fields, "대화 ID", thread.getId(), true);
		addField(fields, "회원 ID", memberId(member), true);
		addField(fields, "닉네임", member != null ? member.getNickname() : null, true);
		addField(fields, "유형", message.getCategory(), true);
		if (!message.getImageKeys().isEmpty()) {
			addField(fields, "사진", message.getImageKeys().size() + "장", true);
		}
		addField(fields, "내용", message.getBody(), false);

		String subject = AdminNotificationMessage.LETTER_SUBJECT;
		publish(
			Channel.LETTER,
			AdminNotificationMessage.title(AdminNotificationMessage.LETTER_EMOJI, subject),
			AdminNotificationMessage.description(subject),
			AdminNotificationMessage.LETTER_COLOR,
			fields);
	}

	private void publish(
			Channel channel,
			String title,
			String description,
			int color,
			List<OperationalNotificationEvent.Field> fields) {
		eventPublisher.publishEvent(new OperationalNotificationEvent(channel, title, description, color, fields));
	}

	private void addField(List<OperationalNotificationEvent.Field> fields, String name, Object value, boolean inline) {
		fields.add(new OperationalNotificationEvent.Field(name, preview(value), inline));
	}

	private String preview(Object value) {
		if (value == null) {
			return "-";
		}
		String text = String.valueOf(value).trim();
		if (text.isEmpty()) {
			return "-";
		}
		if (text.length() <= DETAIL_PREVIEW_LENGTH) {
			return text;
		}
		return text.substring(0, DETAIL_PREVIEW_LENGTH - 3) + "...";
	}

	private Long memberId(Member member) {
		return member != null ? member.getId() : null;
	}

	private String storeLabel(Store store, String fallbackName) {
		if (store == null) {
			return fallbackName;
		}
		return store.getName() + " (#" + store.getId() + ")";
	}

	private String menuLabel(Menu menu, String fallbackName) {
		if (menu == null) {
			return fallbackName;
		}
		return menu.getName() + " (#" + menu.getId() + ")";
	}

	private String firstNotBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second;
	}
}
