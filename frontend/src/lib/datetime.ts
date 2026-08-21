const DATE_TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});

/** Renders an ISO-8601 instant from the API in the viewer's locale. */
export function formatDateTime(value: string): string {
  return DATE_TIME_FORMAT.format(new Date(value));
}
