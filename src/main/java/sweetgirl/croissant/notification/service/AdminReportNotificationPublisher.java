package sweetgirl.croissant.notification.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.inquiry.entity.Inquiry;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;
import sweetgirl.croissant.store.entity.Store;

@Component
@RequiredArgsConstructor
public class AdminReportNotificationPublisher {

	private static final int REPORT_COLOR = 5763719;
	private static final int ABUSE_COLOR = 15158332;
	private static final int INQUIRY_COLOR = 3447003;
	private static final int DETAIL_PREVIEW_LENGTH = 500;

	private final ApplicationEventPublisher eventPublisher;

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

		publish(
			Channel.REPORT,
			"새 가게 제보 접수",
			"Admin Web에서 확인이 필요한 가게 제보가 접수되었습니다.",
			REPORT_COLOR,
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

		publish(
			Channel.REPORT,
			"새 메뉴 제보 접수",
			"Admin Web에서 확인이 필요한 메뉴 제보가 접수되었습니다.",
			REPORT_COLOR,
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

		publish(
			Channel.ABUSE,
			"새 사용자 신고 접수",
			"Admin Web에서 확인이 필요한 사용자 신고가 접수되었습니다.",
			ABUSE_COLOR,
			fields);
	}

	public void publishInquiryCreated(Inquiry inquiry) {
		List<OperationalNotificationEvent.Field> fields = new ArrayList<>();
		addField(fields, "문의 ID", inquiry.getId(), true);
		addField(fields, "카테고리", inquiry.getCategory(), true);
		addField(fields, "회원 ID", memberId(inquiry.getMember()), true);
		addField(fields, "제목", inquiry.getTitle(), false);
		addField(fields, "내용", inquiry.getContent(), false);

		publish(
			Channel.REPORT,
			"새 문의 접수",
			"Admin Web에서 확인이 필요한 문의가 접수되었습니다.",
			INQUIRY_COLOR,
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
