const DATE_TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

/** 把 API 返回的 ISO-8601 时刻按查看者的本地化设置渲染出来。 */
export function formatDateTime(value: string): string {
  return DATE_TIME_FORMAT.format(new Date(value));
}

/** 今天/昨天给相对时间，更早的回到完整日期——「今天 14:20」比完整时间戳更快被读出来。 */
export function formatRelativeDateTime(value: string): string {
  const date = new Date(value);
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const clock = `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  if (date.getTime() >= startOfToday) return `今天 ${clock}`;
  if (date.getTime() >= startOfToday - 86_400_000) return `昨天 ${clock}`;
  return formatDateTime(value);
}
