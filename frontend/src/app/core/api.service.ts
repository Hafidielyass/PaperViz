import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';

import {
  ApiError,
  ConceptView,
  RenderBatchStarted,
  RenderResult,
  RenderStatus,
  PaperDetail,
  PaperSummary,
  SectionAnalysisResult,
  SectionView,
  SystemHealth,
  UploadResponse,
} from './api.models';

/**
 * Single entry point for backend calls. The base path is relative so the same
 * build works behind nginx in compose and behind the dev-server proxy.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api';

  health(): Observable<SystemHealth> {
    return this.http.get<SystemHealth>(`${this.base}/health`);
  }

  listPapers(): Observable<PaperSummary[]> {
    return this.http.get<PaperSummary[]>(`${this.base}/papers`).pipe(catchError(toMessage));
  }

  getPaper(id: string): Observable<PaperDetail> {
    return this.http.get<PaperDetail>(`${this.base}/papers/${id}`).pipe(catchError(toMessage));
  }

  getSections(id: string): Observable<SectionView[]> {
    return this.http.get<SectionView[]>(`${this.base}/papers/${id}/sections`).pipe(catchError(toMessage));
  }

  upload(file: File): Observable<UploadResponse> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<UploadResponse>(`${this.base}/papers`, form).pipe(catchError(toMessage));
  }

  reparse(id: string): Observable<UploadResponse> {
    return this.http
      .post<UploadResponse>(`${this.base}/papers/${id}/reparse`, {})
      .pipe(catchError(toMessage));
  }

  deletePaper(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/papers/${id}`).pipe(catchError(toMessage));
  }

  pdfUrl(id: string): string {
    return `${this.base}/papers/${id}/file`;
  }

  // --- concept extraction and storyboarding ---------------------------------

  /**
   * Analyse one section and wait for the result.
   *
   * Slow by nature — several model calls — so callers should show progress
   * rather than assuming this returns quickly.
   */
  analyzeSection(paperId: string, sectionId: string, storyboard = true): Observable<SectionAnalysisResult> {
    return this.http
      .post<SectionAnalysisResult>(
        `${this.base}/papers/${paperId}/sections/${sectionId}/analyze?storyboard=${storyboard}`,
        {},
      )
      .pipe(catchError(toMessage));
  }

  getSectionConcepts(paperId: string, sectionId: string): Observable<ConceptView[]> {
    return this.http
      .get<ConceptView[]>(`${this.base}/papers/${paperId}/sections/${sectionId}/concepts`)
      .pipe(catchError(toMessage));
  }

  getConcepts(paperId: string): Observable<ConceptView[]> {
    return this.http
      .get<ConceptView[]>(`${this.base}/papers/${paperId}/concepts`)
      .pipe(catchError(toMessage));
  }

  /** Whole-paper analysis. Returns immediately; poll the paper for status. */
  analyzePaper(paperId: string, storyboard = true): Observable<unknown> {
    return this.http
      .post(`${this.base}/papers/${paperId}/analyze?storyboard=${storyboard}`, {})
      .pipe(catchError(toMessage));
  }

  // --- rendering ------------------------------------------------------------

  /** Renders one concept and waits. A Manim render is a minute or two. */
  renderConcept(conceptId: string, quality = 'medium'): Observable<RenderResult> {
    return this.http
      .post<RenderResult>(`${this.base}/concepts/${conceptId}/render?quality=${quality}`, {})
      .pipe(catchError(toMessage));
  }

  renderStatus(conceptId: string): Observable<RenderStatus> {
    return this.http
      .get<RenderStatus>(`${this.base}/concepts/${conceptId}/render`)
      .pipe(catchError(toMessage));
  }

  renderPaper(paperId: string, quality = 'medium'): Observable<RenderBatchStarted> {
    return this.http
      .post<RenderBatchStarted>(`${this.base}/papers/${paperId}/render?quality=${quality}`, {})
      .pipe(catchError(toMessage));
  }

  videoUrl(conceptId: string): string {
    return `${this.base}/concepts/${conceptId}/video`;
  }
}

/**
 * The backend writes rejection messages for the person holding the file, so
 * surface them verbatim rather than replacing them with a generic string.
 */
function toMessage(response: HttpErrorResponse): Observable<never> {
  const body = response.error as ApiError | null;
  const message =
    body && typeof body.message === 'string' && body.message.length > 0
      ? body.message
      : response.status === 0
        ? 'Cannot reach the server.'
        : `Request failed (${response.status}).`;
  return throwError(() => new Error(message));
}
