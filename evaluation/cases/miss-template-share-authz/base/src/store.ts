import { ReportTemplate } from './types';

/** 模板存储口，落地实现走数据库，全部参数化查询。 */
export interface TemplateStore {
  insert(template: ReportTemplate): Promise<void>;
  findById(id: string): Promise<ReportTemplate | null>;
  listByOwner(tenantId: string, ownerId: string): Promise<ReportTemplate[]>;
  update(template: ReportTemplate): Promise<void>;
}
