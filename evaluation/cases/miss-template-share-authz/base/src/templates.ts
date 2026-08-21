import { assertOwnership, HttpError, requireUser } from './auth';
import { TemplateStore } from './store';
import { ReportTemplate, RequestContext } from './types';

/** 报表模板 CRUD 入口。所有按 id 操作均先做归属校验。 */
export class TemplateHandlers {
  constructor(private readonly store: TemplateStore) {}

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
    return template;
  }

  /** GET /templates/:id */
  async getTemplate(ctx: RequestContext): Promise<ReportTemplate> {
    const user = requireUser(ctx);
    const template = await this.store.findById(ctx.params.id);
    if (!template) {
      throw new HttpError(404, '模板不存在');
    }
    assertOwnership(user, template);
    return template;
  }

  /** PUT /templates/:id */
  async updateTemplate(ctx: RequestContext): Promise<ReportTemplate> {
    const user = requireUser(ctx);
    const template = await this.store.findById(ctx.params.id);
    if (!template) {
      throw new HttpError(404, '模板不存在');
    }
    assertOwnership(user, template);
    const body = ctx.body as { name?: string; definition?: string };
    const updated: ReportTemplate = {
      ...template,
      name: body.name ?? template.name,
      definition: body.definition ?? template.definition,
      updatedAt: new Date().toISOString(),
    };
    await this.store.update(updated);
    return updated;
  }

  /** GET /templates */
  async listMine(ctx: RequestContext): Promise<ReportTemplate[]> {
    const user = requireUser(ctx);
    return this.store.listByOwner(user.tenantId, user.id);
  }
}
