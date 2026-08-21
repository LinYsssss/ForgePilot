import { ReportTemplate, ShareGrant } from './types';

/** 模板存储口，落地实现走数据库，全部参数化查询。 */
export interface TemplateStore {
  insert(template: ReportTemplate): Promise<void>;
  findById(id: string): Promise<ReportTemplate | null>;
  listByOwner(tenantId: string, ownerId: string): Promise<ReportTemplate[]>;
  update(template: ReportTemplate): Promise<void>;
  deleteById(id: string): Promise<void>;
}

/** 分享授权存储口。 */
export interface ShareGrantStore {
  insert(grant: ShareGrant): Promise<void>;
  remove(templateId: string, grantedTo: string): Promise<void>;
  removeAllForTemplate(templateId: string): Promise<void>;
  listGrantsFor(tenantId: string, grantedTo: string): Promise<ShareGrant[]>;
  exists(templateId: string, grantedTo: string): Promise<boolean>;
}
