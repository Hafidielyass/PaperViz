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
export const IN_PROGRESS: readonly PaperStatus[] = ['UPLOADED', 'PARSING', 'ANALYZING', 'RENDERING'];

export function isInProgress(status: PaperStatus): boolean {
  return IN_PROGRESS.includes(status);
}
