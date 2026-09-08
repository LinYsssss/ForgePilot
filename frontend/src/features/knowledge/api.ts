import { requestJson } from "../../lib/http";

export type KnowledgeSourceType = "PROJECT_KNOWLEDGE" | "REQUIREMENT_ATTACHMENT";
export type KnowledgeStatus = "PENDING" | "READY" | "FAILED";

/** List model exposes metadata; content is fetched on demand. Raw embeddings never leave the server. */
export interface KnowledgeDocument {
  id: number;
  projectId: number;
  sourceType: KnowledgeSourceType;
  sourceRequirementId: number | null;
  title: string;
  status: KnowledgeStatus;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  chunkCount: number;
  embeddedChunkCount: number;
  embeddingDimension: number | null;
  embeddingProvider: string | null;
  embeddingModel: string | null;
  embeddingVersion: string | null;
}

interface KnowledgeDocumentInput { title: string; text: string; }

function projectPath(projectId: number): string { return `/api/projects/${projectId}`; }

export function listProjectKnowledge(projectId: number): Promise<KnowledgeDocument[]> {
  return requestJson<KnowledgeDocument[]>(`${projectPath(projectId)}/knowledge/documents`);
}

export function uploadProjectKnowledge(projectId: number, input: KnowledgeDocumentInput): Promise<KnowledgeDocument> {
  return requestJson<KnowledgeDocument>(`${projectPath(projectId)}/knowledge/documents`, { method: "POST", body: JSON.stringify(input) });
}

export function deleteProjectKnowledge(projectId: number, documentId: number): Promise<void> {
  return requestJson<void>(`${projectPath(projectId)}/knowledge/documents/${documentId}`, {
    method: "DELETE",
  });
}

export interface KnowledgeDocumentContent { documentId: number; title: string; text: string; }

export function getKnowledgeContent(projectId: number, documentId: number): Promise<KnowledgeDocumentContent> {
  return requestJson<KnowledgeDocumentContent>(`${projectPath(projectId)}/knowledge/documents/${documentId}/content`);
}

export function knowledgeDownloadUrl(projectId: number, documentId: number): string {
  return `${projectPath(projectId)}/knowledge/documents/${documentId}/download`;
}
