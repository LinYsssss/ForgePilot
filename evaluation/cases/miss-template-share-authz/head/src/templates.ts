import { AuditSink } from './audit';
import { assertOwnership, HttpError, requireUser } from './auth';
import { ShareGrantStore, TemplateStore } from './store';
import { ReportTemplate, RequestContext, ShareGrant } from './types';

/**
 * 报表模板 CRUD + 分享入口。
 *
 * <p>所有按 id 操作单个模板的入口先做归属校验（assertOwnership）；
 * 分享只授予只读访问；增删改与分享变更全部落审计。
 */
export class TemplateHandlers {
  constructor(
    private readonly store: TemplateStore,
    private readonly grants: ShareGrantStore,
    private readonly audit: AuditSink,
  ) {}

  /** POST /templates */
  async createTemplate(ctx: RequestContext): Promise<ReportTemplate> {
    const user = requireUser(ctx);
    const body = ctx.body as { name?: string; definition?: string };
    if (!body.name || !body.definition) {
      throw new HttpError(400, 'name 与 definition 必填');
    }
    const template: ReportTemplate = {
      id: crypto.randomUUID(),
      tenantId: user.tenantId,
      ownerId: user.id,
      name: body.name,
      definition: body.definition,
      updatedAt: new Date().toISOString(),
    };
    await this.store.insert(template);
    await this.audit.record({
      action: 'TEMPLATE_CREATE',
      actorId: user.id,
      tenantId: user.tenantId,
      targetId: template.id,
    });
    return template;
  }

  /** GET /templates/:id —— owner/管理员，或持有分享授权的同租户用户。 */
  async getTemplate(ctx: RequestContext): Promise<ReportTemplate> {
    const user = requireUser(ctx);
    const template = await this.loadOr404(ctx.params.id);
    if (
      template.tenantId === user.tenantId &&
      (await this.grants.exists(template.id, user.id))
    ) {
      return template;
    }
    assertOwnership(user, template);
    return template;
  }

  /** PUT /templates/:id */
  async updateTemplate(ctx: RequestContext): Promise<ReportTemplate> {
    const user = requireUser(ctx);
    const template = await this.loadOr404(ctx.params.id);
    assertOwnership(user, template);
    const body = ctx.body as { name?: string; definition?: string };
    const updated: ReportTemplate = {
      ...template,
      name: body.name ?? template.name,
      definition: body.definition ?? template.definition,
      updatedAt: new Date().toISOString(),
    };
    await this.store.update(updated);
    await this.audit.record({
      action: 'TEMPLATE_UPDATE',
      actorId: user.id,
      tenantId: user.tenantId,
      targetId: template.id,
    });
    return updated;
  }

  /** DELETE /templates/:id —— 连带清理全部分享授权。 */
  async removeTemplate(ctx: RequestContext): Promise<void> {
    const user = requireUser(ctx);
    const template = await this.loadOr404(ctx.params.id);
    await this.grants.removeAllForTemplate(template.id);
    await this.store.deleteById(template.id);
    await this.audit.record({
      action: 'TEMPLATE_DELETE',
      actorId: user.id,
      tenantId: user.tenantId,
      targetId: template.id,
    });
  }

  /** POST /templates/:id/share */
  async shareTemplate(ctx: RequestContext): Promise<ShareGrant> {
    const user = requireUser(ctx);
    const template = await this.loadOr404(ctx.params.id);
    assertOwnership(user, template);
    const body = ctx.body as { grantedTo?: string };
    if (!body.grantedTo) {
      throw new HttpError(400, 'grantedTo 必填');
    }
    const grant: ShareGrant = {
      templateId: template.id,
      tenantId: template.tenantId,
      grantedTo: body.grantedTo,
      grantedBy: user.id,
      grantedAt: new Date().toISOString(),
    };
    await this.grants.insert(grant);
    await this.audit.record({
      action: 'TEMPLATE_SHARE',
      actorId: user.id,
      tenantId: user.tenantId,
      targetId: template.id,
      detail: `grantedTo=${body.grantedTo}`,
    });
    return grant;
  }

  /** DELETE /templates/:id/share/:grantedTo */
  async revokeShare(ctx: RequestContext): Promise<void> {
    const user = requireUser(ctx);
    const template = await this.loadOr404(ctx.params.id);
    assertOwnership(user, template);
    await this.grants.remove(template.id, ctx.params.grantedTo);
    await this.audit.record({
      action: 'TEMPLATE_UNSHARE',
      actorId: user.id,
      tenantId: user.tenantId,
      targetId: template.id,
      detail: `grantedTo=${ctx.params.grantedTo}`,
    });
  }

  /** GET /templates/shared-with-me —— 仅返回分享授权命中的模板。 */
  async listSharedWithMe(ctx: RequestContext): Promise<ReportTemplate[]> {
    const user = requireUser(ctx);
    const grantList = await this.grants.listGrantsFor(user.tenantId, user.id);
    const result: ReportTemplate[] = [];
    for (const grant of grantList) {
      const template = await this.store.findById(grant.templateId);
      if (template && template.tenantId === user.tenantId) {
        result.push(template);
      }
    }
    return result;
  }

  /** POST /templates/bulk-export —— 逐一归属校验后导出。 */
  async bulkExport(ctx: RequestContext): Promise<ReportTemplate[]> {
    const user = requireUser(ctx);
    const body = ctx.body as { ids?: string[] };
    if (!body.ids || body.ids.length === 0) {
      throw new HttpError(400, 'ids 必填');
    }
    if (body.ids.length > 50) {
      throw new HttpError(400, '单次最多导出 50 个模板');
    }
    const result: ReportTemplate[] = [];
    for (const id of body.ids) {
      const template = await this.loadOr404(id);
      assertOwnership(user, template);
      result.push(template);
    }
    return result;
  }

  /** GET /templates */
  async listMine(ctx: RequestContext): Promise<ReportTemplate[]> {
    const user = requireUser(ctx);
    return this.store.listByOwner(user.tenantId, user.id);
  }

  private async loadOr404(id: string): Promise<ReportTemplate> {
    const template = await this.store.findById(id);
    if (!template) {
      throw new HttpError(404, '模板不存在');
    }
    return template;
  }
}
