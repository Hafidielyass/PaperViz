import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { PaperSummary, isInProgress } from '../../core/api.models';

@Component({
  selector: 'pv-library',
  imports: [RouterLink, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .card {
        border: 1px solid var(--pv-border);
        background: var(--pv-surface);
      }
      .dropzone {
        border: 2px dashed var(--pv-border);
        transition: border-color 0.15s ease, background 0.15s ease;
      }
      .dropzone.active {
        border-color: #10b981;
        background: rgba(16, 185, 129, 0.06);
      }
    `,
  ],
  template: `
    <section class="mx-auto max-w-3xl px-6 py-12">
      <header class="mb-8 flex items-baseline justify-between gap-4">
        <div>
          <h1 class="text-3xl font-semibold tracking-tight">PaperViz</h1>
          <p class="mt-1 text-sm" style="color: var(--pv-muted)">
            Upload a paper you have access to. PaperViz extracts its structure with GROBID.
          </p>
        </div>
        <a routerLink="/health" class="shrink-0 text-xs underline" style="color: var(--pv-muted)">
          system health
        </a>
      </header>

      <!-- Upload -->
      <div
        class="dropzone mb-3 rounded-xl p-8 text-center"
        [class.active]="dragging()"
        (dragover)="onDragOver($event)"
        (dragleave)="onDragLeave($event)"
        (drop)="onDrop($event)"
      >
        <p class="text-sm font-medium">Drop a PDF here</p>
        <p class="mt-1 text-xs" style="color: var(--pv-muted)">or</p>
        <label
          class="mt-3 inline-block cursor-pointer rounded-md px-4 py-2 text-sm font-medium"
          style="background: #10b981; color: #04120c"
        >
          Choose a file
          <input type="file" accept="application/pdf,.pdf" class="hidden" (change)="onPick($event)" />
        </label>
        <p class="mt-4 text-xs" style="color: var(--pv-muted)">
          Your file is processed for your account only. It is never re-hosted or shared.
          Pasting a link is supported for open-access sources — that arrives in a later stage.
        </p>
      </div>

      @if (uploading()) {
        <p class="mb-3 text-sm" style="color: var(--pv-muted)">Uploading {{ uploadingName() }}…</p>
      }
      @if (error(); as e) {
        <div class="mb-3 rounded-lg border border-red-400/60 bg-red-500/10 p-4 text-sm text-red-500">
          {{ e }}
        </div>
      }
      @if (notice(); as n) {
        <div
          class="mb-3 rounded-lg border border-emerald-400/60 bg-emerald-500/10 p-4 text-sm text-emerald-500"
        >
          {{ n }}
        </div>
      }

      <!-- Library -->
      <h2 class="mt-10 mb-3 text-sm font-semibold tracking-wide uppercase" style="color: var(--pv-muted)">
        Your papers
      </h2>

      @if (papers(); as list) {
        @if (list.length === 0) {
          <p class="rounded-lg p-6 text-center text-sm card" style="color: var(--pv-muted)">
            Nothing here yet.
          </p>
        } @else {
          <ul class="space-y-2">
            @for (p of list; track p.id) {
              <li class="card rounded-lg p-4">
                <div class="flex items-start justify-between gap-4">
                  <a [routerLink]="['/read', p.id]" class="min-w-0 flex-1 hover:underline">
                    <p class="truncate font-medium">{{ p.title || p.originalFilename || 'Untitled' }}</p>
                    @if (p.authors) {
                      <p class="mt-0.5 truncate text-xs" style="color: var(--pv-muted)">{{ p.authors }}</p>
                    }
                    <p class="mt-1 text-xs" style="color: var(--pv-muted)">
                      {{ p.createdAt | date: 'medium' }}
                      @if (p.sectionCount > 0) {
                        · {{ p.sectionCount }} sections
                      }
                    </p>
                  </a>
                  <div class="flex shrink-0 flex-col items-end gap-2">
                    <span
                      class="rounded-full px-2 py-0.5 text-xs font-medium"
                      [style.background]="statusBg(p)"
                      [style.color]="statusFg(p)"
                    >
                      {{ p.status }}
                    </span>
                    <button
                      type="button"
                      class="text-xs underline"
                      style="color: var(--pv-muted)"
                      (click)="remove(p)"
                    >
                      delete
                    </button>
                  </div>
                </div>
                @if (p.statusDetail && p.status === 'FAILED') {
                  <p class="mt-2 text-xs text-red-500">{{ p.statusDetail }}</p>
                }
              </li>
            }
          </ul>
        }
      } @else {
        <p class="text-sm" style="color: var(--pv-muted)">Loading…</p>
      }
    </section>
  `,
})
export class LibraryPage {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly refresh = new Subject<void>();

  readonly papers = signal<PaperSummary[] | null>(null);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly dragging = signal(false);
  readonly uploading = signal(false);
  readonly uploadingName = signal('');

  constructor() {
    // Poll while anything is still processing; GROBID takes tens of seconds.
    timer(0, 3000)
      .pipe(
        switchMap(() => this.api.listPapers()),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (list) => this.papers.set(list),
        error: (e: Error) => this.error.set(e.message),
      });
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) {
      this.send(file);
    }
  }

  onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      this.send(file);
    }
    // Allow re-picking the same file after a failure.
    input.value = '';
  }

  private send(file: File): void {
    this.error.set(null);
    this.notice.set(null);
    this.uploading.set(true);
    this.uploadingName.set(file.name);

    this.api.upload(file).subscribe({
      next: (res) => {
        this.uploading.set(false);
        this.notice.set(res.message);
        // Straight to the explainer. The verbatim section view at /papers/:id
        // is a developer surface; nobody uploads a paper to read it back.
        void this.router.navigate(['/read', res.paperId]);
      },
      error: (e: Error) => {
        this.uploading.set(false);
        this.error.set(e.message);
      },
    });
  }

  remove(paper: PaperSummary): void {
    this.api.deletePaper(paper.id).subscribe({
      next: () => this.papers.update((list) => (list ?? []).filter((p) => p.id !== paper.id)),
      error: (e: Error) => this.error.set(e.message),
    });
  }

  statusBg(p: PaperSummary): string {
    if (p.status === 'FAILED') return 'rgba(239, 68, 68, 0.15)';
    if (isInProgress(p.status)) return 'rgba(245, 158, 11, 0.15)';
    return 'rgba(16, 185, 129, 0.15)';
  }

  statusFg(p: PaperSummary): string {
    if (p.status === 'FAILED') return '#ef4444';
    if (isInProgress(p.status)) return '#f59e0b';
    return '#10b981';
  }
}
