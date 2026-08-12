/** 审计日志出口：模板增删改与分享变更全部留痕。 */

export interface AuditEvent {
  action: string;
  actorId: string;
  tenantId: string;
  targetId: string;
  detail?: string;
}

export interface AuditSink {
  record(event: AuditEvent): Promise<void>;
}

/** 控制台落地实现（生产替换为消息总线）。不打印模板 definition 内容。 */
export class ConsoleAuditSink implements AuditSink {
  async record(event: AuditEvent): Promise<void> {
    console.info(
      `[audit] ${event.action} actor=${event.actorId} tenant=${event.tenantId}` +
        ` target=${event.targetId}${event.detail ? ' ' + event.detail : ''}`,
    );
  }
}
