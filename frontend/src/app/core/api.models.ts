/** Mirrors the JSON returned by GET /api/health on the Spring Boot side. */
export interface DependencyHealth {
  status: 'UP' | 'DOWN';
  detail?: string;
  error?: string;
  latencyMs: number;
}

export interface SystemHealth {
  service: string;
  timestamp: string;
  status: 'UP' | 'DEGRADED';
  dependencies: Record<string, DependencyHealth>;
}

export type PaperStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'PARSED'
  | 'ANALYZING'
  | 'ANALYZED'
  // Turning the parsed paper into the explainer segments a reader sees.
  | 'WRITING'
  | 'WRITTEN'
  | 'RENDERING'
  | 'READY'
  | 'FAILED';

export interface PaperSummary {
  id: string;
  title: string | null;
  authors: string | null;
  sourceType: 'UPLOAD' | 'OPEN_ACCESS_URL';
  originalFilename: string | null;
  status: PaperStatus;
  statusDetail: string | null;
  sectionCount: number;
  createdAt: string;
}

export interface SectionView {
  id: string;
  ordinal: number;
  type: string;
  heading: string | null;
  text: string;
  latex: string | null;
  wordCount: number;
}

export interface PaperDetail {
  paper: PaperSummary;
  sections: SectionView[];
}

export interface UploadResponse {
  paperId: string;
  status: PaperStatus;
  cacheHit: boolean;
  message: string;
}

export interface ApiError {
  error: string;
  message: string;
}

/** Statuses where the pipeline is still working and the client should keep polling. */
export const IN_PROGRESS: readonly PaperStatus[] = [
  'UPLOADED',
  'PARSING',
  'ANALYZING',
  'WRITING',
  'RENDERING',
];

export function isInProgress(status: PaperStatus): boolean {
  return IN_PROGRESS.includes(status);
}

// ---------------------------------------------------------------------------
// Concept extraction and storyboarding (stage 3)
// ---------------------------------------------------------------------------

export type ConceptType =
  | 'EQUATION'
  | 'ARCHITECTURE'
  | 'ALGORITHM'
  | 'RESULT'
  | 'INTUITION'
  | 'OTHER';

export interface StoryboardBeat {
  order: number;
  seconds: number;
  visual: string;
  narration: string;
  latex: string | null;
}

export interface Storyboard {
  title: string;
  summary: string;
  totalSeconds: number;
  beats: StoryboardBeat[];
}

/**
 * When every generation attempt was rejected, the backend stores the validator's
 * complaints in the same column instead of a storyboard.
 */
export interface StoryboardRejection {
  problems: string[];
}

export interface ConceptView {
  id: string;
  sectionId: string;
  ordinal: number;
  title: string;
  description: string | null;
  conceptType: ConceptType;
  animate: boolean;
  storyboard: Storyboard | StoryboardRejection | null;
  narration: string | null;
}

export interface SectionAnalysisResult {
  sectionId: string;
  conceptsFound: number;
  storyboardsBuilt: number;
  elapsedMs: number;
  concepts: ConceptView[];
}

export function isRejection(
  value: Storyboard | StoryboardRejection | null,
): value is StoryboardRejection {
  return value !== null && 'problems' in value;
}

export function isStoryboard(
  value: Storyboard | StoryboardRejection | null,
): value is Storyboard {
  return value !== null && 'beats' in value;
}

// ---------------------------------------------------------------------------
// Rendering (stage 4)
// ---------------------------------------------------------------------------

export type RenderState = 'NONE' | 'PENDING' | 'GENERATING' | 'VALIDATING' | 'RENDERING' | 'READY' | 'FAILED';

export interface RenderResult {
  conceptId: string;
  ok: boolean;
  videoUrl: string | null;
  message: string | null;
  elapsedMs: number;
}

export interface RenderStatus {
  conceptId: string;
  status: RenderState;
  videoUrl: string | null;
  durationSeconds: number | null;
  retryCount: number;
  lastError: string | null;
}

export interface RenderBatchStarted {
  paperId: string;
  queued: number;
  message: string;
}

// ---------------------------------------------------------------------------
// The explainer: what the reader actually sees
// ---------------------------------------------------------------------------

export interface SegmentView {
  id: string;
  ordinal: number;
  title: string;
  /** Plain-language prose. Paragraphs separated by a blank line. */
  body: string;
  keyTakeaway: string | null;
  latex: string | null;
  sourceOrdinals: number[];
  storyboard: Storyboard | StoryboardRejection | null;
  narration: string | null;
  videoUrl: string;
}

export interface Explainer {
  paper: PaperSummary;
  segments: SegmentView[];
  defaultSegmentCount: number;
}

export interface WriteStarted {
  paperId: string;
  count: number;
  storyboard: boolean;
  message: string;
}
