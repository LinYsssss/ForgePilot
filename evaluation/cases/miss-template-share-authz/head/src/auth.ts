import { ReportTemplate, RequestContext, User } from './types';

/** 带状态码的业务错误，网关层直接映射为 HTTP 响应。 */
export class HttpError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

/** 取当前已认证用户，未认证抛 401。 */
export function requireUser(ctx: RequestContext): User {
  if (!ctx.user) {
    throw new HttpError(401, '未认证');
  }
  return ctx.user;
}

/**
 * 归属校验：仅模板 owner 或同租户 TEMPLATE_ADMIN 可操作，
 * 其余一律 403。所有按 id 操作单个模板的入口都必须调用。
 */
export function assertOwnership(user: User, template: ReportTemplate): void {
  if (template.tenantId !== user.tenantId) {
    throw new HttpError(404, '模板不存在');
  }
  if (template.ownerId === user.id) {
    return;
  }
  if (user.roles.includes('TEMPLATE_ADMIN')) {
    return;
  }
  throw new HttpError(403, '无权操作该模板');
}
