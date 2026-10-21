package sweetgirl.dataops.snapshot.parser;

/** 운영 store_business_hour 한 행에 대응하는 정규화 영업시간 — dayOfWeek 는 MONDAY..SUNDAY, 시간은 "HH:mm" */
public record BusinessHourRow(String dayOfWeek, String openTime, String closeTime, boolean closed) {
}
