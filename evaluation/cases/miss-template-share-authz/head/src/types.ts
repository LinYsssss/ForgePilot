/** 报表模板中心 —— 领域类型。 */

export interface User {
  id: string;
  tenantId: string;
  roles: string[];
}

export interface ReportTemplate {
  id: string;
  tenantId: string;
  ownerId: string;
  name: string;
  /** 序列化后的图表/字段配置。 */
  definition: string;
  updatedAt: string;
}

/** 模板分享授权：owner 将只读访问授予同租户其他用户。 */
export interface ShareGrant {
  templateId: string;
  tenantId: string;
  grantedTo: string;
  grantedBy: string;
  grantedAt: string;
}

export interface RequestContext {
  /** 网关注入的已认证用户；未认证时为 undefined。 */
  user?: User;
  params: Record<string, string>;
  body: unknown;
}
