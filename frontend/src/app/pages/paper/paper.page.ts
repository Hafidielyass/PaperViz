import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { PaperDetail, isInProgress } from '../../core/api.models';

@Component({
  selector: 'pv-paper',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .card {
        border: 1px solid var(--pv-border);
        background: var(--pv-surface);
      }
      .prose-text {
        line-height: 1.7;
        white-space: pre-wrap;
      }
      .latex {
        font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
        font-size: 0.8rem;
        white-space: pre-wrap;
        word-break: break-word;
      }
    `,
  ],
  template: `
    <section class="mx-auto max-w-3xl px-6 py-12">
      <a routerLink="/" class="text-xs underline" style="color: var(--pv-muted)">&larr; all papers</a>

      @if (error(); as e) {
        <div class="mt-6 rounded-lg border border-red-400/60 bg-red-500/10 p-4 text-sm text-red-500">
          {{ e }}
        </div>
      }

      @if (detail(); as d) {
        <header class="mt-4 mb-8">
          <h1 class="text-2xl font-semibold tracking-tight">
            {{ d.paper.title || d.paper.originalFilename || 'Untitled' }}
          </h1>
          @if (d.paper.authors) {
            <p class="mt-1 text-sm" style="color: var(--pv-muted)">{{ d.paper.authors }}</p>
          }

          <div class="mt-4 flex flex-wrap items-center gap-3 text-xs">
            <span
              class="rounded-full px-2 py-0.5 font-medium"
              [style.background]="statusBg()"
              [style.color]="statusFg()"
            >
              {{ d.paper.status }}
            </span>
            @if (d.paper.statusDetail) {
              <span style="color: var(--pv-muted)">{{ d.paper.statusDetail }}</span>
            }
            <a [href]="pdfUrl()" target="_blank" rel="noopener" class="underline" style="color: var(--pv-muted)">
              original PDF
            </a>
            <button type="button" class="underline" style="color: var(--pv-muted)" (click)="reparse()">
              re-parse
            </button>
          </div>

          @if (busy()) {
            <p class="mt-4 text-sm" style="color: var(--pv-muted)">
              Extracting structure — this takes GROBID a moment on CPU. Updating automatically.
            </p>
          }
        </header>

        @if (d.sections.length > 0) {
          <!-- Section type distribution, a quick sanity read on parse quality -->
          <div class="card mb-8 rounded-lg p-4">
            <p class="mb-2 text-xs font-semibold tracking-wide uppercase" style="color: var(--pv-muted)">
              {{ d.sections.length }} sections · {{ totalWords() }} words
            </p>
            <div class="flex flex-wrap gap-1.5">
              @for (t of typeCounts(); track t.type) {
                <span
                  class="rounded px-1.5 py-0.5 text-xs"
                  style="background: rgba(127,127,127,0.12); color: var(--pv-muted)"
                >
                  {{ t.type }} ×{{ t.count }}
                </span>
              }
            </div>
          </div>

          <ol class="space-y-6">
            @for (s of d.sections; track s.id) {
              <li class="card rounded-lg p-5">
                <div class="mb-2 flex items-baseline justify-between gap-3">
                  <h2 class="font-medium">
                    <span style="color: var(--pv-muted)">{{ s.ordinal }}.</span>
                    {{ s.heading || 'Untitled section' }}
                  </h2>
                  <span class="shrink-0 text-xs" style="color: var(--pv-muted)">
                    {{ s.type }} · {{ s.wordCount }}w
                  </span>
                </div>

                <p class="prose-text text-sm">{{ s.text }}</p>

                @if (s.latex) {
                  <details class="mt-3">
                    <summary class="cursor-pointer text-xs" style="color: var(--pv-muted)">
                      formulas
                    </summary>
                    <pre class="latex mt-2 overflow-x-auto rounded p-3" style="background: rgba(127,127,127,0.08)">{{ s.latex }}</pre>
                  </details>
                }
              </li>
            }
          </ol>
        } @else if (!busy()) {
          <p class="card rounded-lg p-6 text-center text-sm" style="color: var(--pv-muted)">
            No sections were extracted.
          </p>
        }
      } @else if (!error()) {
        <p class="mt-6 text-sm" style="color: var(--pv-muted)">Loading…</p>
      }
    </section>
  `,
})
export class PaperPage {
  private readonly api = inject(ApiService);

  /** Bound from the :id route parameter via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly detail = signal<PaperDetail | null>(null);
  readonly error = signal<string | null>(null);

  readonly busy = computed(() => {
    const d = this.detail();
    return d ? isInProgress(d.paper.status) : false;
  });

  readonly totalWords = computed(() =>
    (this.detail()?.sections ?? []).reduce((sum, s) => sum + s.wordCount, 0),
  );

  readonly typeCounts = computed(() => {
    const counts = new Map<string, number>();
    for (const s of this.detail()?.sections ?? []) {
      counts.set(s.type, (counts.get(s.type) ?? 0) + 1);
    }
    return [...counts.entries()]
      .map(([type, count]) => ({ type, count }))
      .sort((a, b) => b.count - a.count);
  });

  constructor() {
    timer(0, 3000)
      .pipe(
        switchMap(() => this.api.getPaper(this.id())),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (d) => {
          this.detail.set(d);
          this.error.set(null);
        },
        error: (e: Error) => this.error.set(e.message),
      });
  }

  pdfUrl(): string {
    return this.api.pdfUrl(this.id());
  }

  reparse(): void {
    this.api.reparse(this.id()).subscribe({
      error: (e: Error) => this.error.set(e.message),
    });
  }

  statusBg(): string {
    const s = this.detail()?.paper.status;
    if (s === 'FAILED') return 'rgba(239, 68, 68, 0.15)';
    if (s && isInProgress(s)) return 'rgba(245, 158, 11, 0.15)';
    return 'rgba(16, 185, 129, 0.15)';
  }

  statusFg(): string {
    const s = this.detail()?.paper.status;
    if (s === 'FAILED') return '#ef4444';
    if (s && isInProgress(s)) return '#f59e0b';
    return '#10b981';
  }
}
