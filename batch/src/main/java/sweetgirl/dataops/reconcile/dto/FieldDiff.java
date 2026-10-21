package sweetgirl.dataops.reconcile.dto;

/** 필드 단위 diff — target 체계는 change_proposal/apply_log 와 동일 ('store.phone' | 'menu.{id}.price' | 'menu.add.{이름}' …) */
public record FieldDiff(
		String target,
		String oldValue,
		String newValue) {
}
