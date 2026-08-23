import type { Coverage } from "./api";

export interface ReviewContextRequirement {
  id: number;
  revisionId: number;
  title: string;
  background: string | null;
  description: string | null;
}

export interface ReviewContextCriterion {
  id: number;
  acKey: string;
  text: string;
}

export interface ReviewContextPullRequest {
  provider: string;
  instance: string;
  repository: string;
  number: number;
  baseSha: string;
  headSha: string;
  inputFingerprint: string;
  title: string;
}

export interface ReviewContextChangedFile {
  path: string;
  changeType: string;
  patch: string | null;
}

export interface ReviewContextKnowledge {
  sourceId: number;
  documentId: number;
  chunkId: number;
  excerpt: string;
  score: number;
}

export interface ReviewContextSnapshot {
  requirement: ReviewContextRequirement | null;
  acceptanceCriteria: ReviewContextCriterion[];
  pullRequest: ReviewContextPullRequest;
  changedFiles: ReviewContextChangedFile[];
  knowledgeEvidence: ReviewContextKnowledge[];
  truncation: Coverage | null;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === "string";
}

function isNullableString(value: unknown): value is string | null {
  return value === null || isString(value);
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isPositiveInteger(value: unknown): value is number {
  return Number.isInteger(value) && Number(value) > 0;
}

function requirementOf(value: unknown): ReviewContextRequirement | null | undefined {
  if (value === null) {
    return null;
  }
  if (
    !isObject(value) ||
    !isPositiveInteger(value.id) ||
    !isPositiveInteger(value.revisionId) ||
    !isString(value.title) ||
    !isNullableString(value.background) ||
    !isNullableString(value.description)
  ) {
    return undefined;
  }
  return {
    id: value.id,
    revisionId: value.revisionId,
    title: value.title,
    background: value.background,
    description: value.description,
  };
}

function criterionOf(value: unknown): ReviewContextCriterion | null {
  if (
    !isObject(value) ||
    !isPositiveInteger(value.id) ||
    !isString(value.acKey) ||
    !isString(value.text)
  ) {
    return null;
  }
  return { id: value.id, acKey: value.acKey, text: value.text };
}

function pullRequestOf(value: unknown): ReviewContextPullRequest | null {
  if (
    !isObject(value) ||
    !isString(value.provider) ||
    !isString(value.instance) ||
    !isString(value.repository) ||
    !isPositiveInteger(value.number) ||
    !isString(value.baseSha) ||
    !isString(value.headSha) ||
    !isString(value.inputFingerprint) ||
    !isString(value.title)
  ) {
    return null;
  }
  return {
    provider: value.provider,
    instance: value.instance,
    repository: value.repository,
    number: value.number,
    baseSha: value.baseSha,
    headSha: value.headSha,
    inputFingerprint: value.inputFingerprint,
    title: value.title,
  };
}

function changedFileOf(value: unknown): ReviewContextChangedFile | null {
  if (
    !isObject(value) ||
    !isString(value.path) ||
    !isString(value.changeType) ||
    !isNullableString(value.patch)
  ) {
    return null;
  }
  return { path: value.path, changeType: value.changeType, patch: value.patch };
}

function knowledgeOf(value: unknown): ReviewContextKnowledge | null {
  if (
    !isObject(value) ||
    !isPositiveInteger(value.sourceId) ||
    !isPositiveInteger(value.documentId) ||
    !isPositiveInteger(value.chunkId) ||
    !isString(value.excerpt) ||
    !isFiniteNumber(value.score)
  ) {
    return null;
  }
  return {
    sourceId: value.sourceId,
    documentId: value.documentId,
    chunkId: value.chunkId,
    excerpt: value.excerpt,
    score: value.score,
  };
}

function coverageOf(value: unknown): Coverage | null | undefined {
  if (value === null) {
    return null;
  }
  if (!isObject(value) || typeof value.truncated !== "boolean") {
    return undefined;
  }
  if (!Array.isArray(value.files) || !Array.isArray(value.notReviewed)) {
    return undefined;
  }
  const files = value.files.map((file) => {
    if (
      !isObject(file) ||
      !isString(file.path) ||
      typeof file.patchTruncated !== "boolean"
    ) {
      return null;
    }
    return { path: file.path, patchTruncated: file.patchTruncated };
  });
  if (files.some((file) => file === null) || !value.notReviewed.every(isString)) {
    return undefined;
  }
  return {
    truncated: value.truncated,
    files: files.filter((file) => file !== null),
    notReviewed: value.notReviewed,
  };
}

function arrayOf<T>(value: unknown, parse: (entry: unknown) => T | null): T[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const parsed = value.map(parse);
  return parsed.some((entry) => entry === null)
    ? null
    : parsed.filter((entry) => entry !== null);
}

/**
 * 在它的归属处对外部 JSON 做一次收窄。视图绝不对不可变 Review 上下文中的
 * 字段做类型断言或私自重新解读。
 */
export function parseReviewContext(value: unknown): ReviewContextSnapshot | null {
  if (!isObject(value)) {
    return null;
  }
  const requirement = requirementOf(value.requirement);
  const acceptanceCriteria = arrayOf(value.acceptanceCriteria, criterionOf);
  const pullRequest = pullRequestOf(value.pullRequest);
  const changedFiles = arrayOf(value.changedFiles, changedFileOf);
  const knowledgeEvidence = arrayOf(value.knowledgeEvidence, knowledgeOf);
  const truncation = coverageOf(value.truncation);
  if (
    requirement === undefined ||
    acceptanceCriteria === null ||
    pullRequest === null ||
    changedFiles === null ||
    knowledgeEvidence === null ||
    truncation === undefined
  ) {
    return null;
  }
  return {
    requirement,
    acceptanceCriteria,
    pullRequest,
    changedFiles,
    knowledgeEvidence,
    truncation,
  };
}
