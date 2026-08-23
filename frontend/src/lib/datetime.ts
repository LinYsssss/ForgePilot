const DATE_TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

/** 把 API 返回的 ISO-8601 时刻按查看者的本地化设置渲染出来。 */
export function formatDateTime(value: string): string {
  return DATE_TIME_FORMAT.format(new Date(value));
}
