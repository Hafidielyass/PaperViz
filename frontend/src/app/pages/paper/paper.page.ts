import {
  ChangeDetectionStrategy,
  Component,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { ConceptView, PaperDetail, isInProgress } from '../../core/api.models';
import { ConceptCard } from './concept-card';

@Component({
  selector: 'pv-paper',
  imports: [RouterLink, ConceptCard],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .card {
        border: 1px solid var(--el-border);
        background: var(--el-surface);
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
      <a routerLink="/" class="text-xs underline" style="color: var(--el-muted)">&larr; all papers</a>

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
            <p class="mt-1 text-sm" style="color: var(--el-muted)">{{ d.paper.authors }}</p>
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
              <span style="color: var(--el-muted)">{{ d.paper.statusDetail }}</span>
            }
            <a [href]="pdfUrl()" target="_blank" rel="noopener" class="underline" style="color: var(--el-muted)">
              original PDF
            </a>
            <button type="button" class="underline" style="color: var(--el-muted)" (click)="reparse()">
              re-parse
            </button>
          </div>

          @if (busy()) {
            <p class="mt-4 text-sm" style="color: var(--el-muted)">
              Extracting structure — this takes GROBID a moment on CPU. Updating automatically.
            </p>
          }
        </header>

        @if (d.sections.length > 0) {
          <!-- Section type distribution, a quick sanity read on parse quality -->
          <div class="card mb-8 rounded-lg p-4">
            <p class="mb-2 text-xs font-semibold tracking-wide uppercase" style="color: var(--el-muted)">
              {{ d.sections.length }} sections · {{ totalWords() }} words
            </p>
            <div class="flex flex-wrap gap-1.5">
              @for (t of typeCounts(); track t.type) {
                <span
                  class="rounded px-1.5 py-0.5 text-xs"
                  style="background: rgba(127,127,127,0.12); color: var(--el-muted)"
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
                    <span style="color: var(--el-muted)">{{ s.ordinal }}.</span>
                    {{ s.heading || 'Untitled section' }}
                  </h2>
                  <span class="shrink-0 text-xs" style="color: var(--el-muted)">
                    {{ s.type }} · {{ s.wordCount }}w
                  </span>
                </div>

                <p class="prose-text text-sm">{{ s.text }}</p>

                @if (s.latex) {
                  <details class="mt-3">
                    <summary class="cursor-pointer text-xs" style="color: var(--el-muted)">
                      formulas
                    </summary>
                    <pre class="latex mt-2 overflow-x-auto rounded p-3" style="background: rgba(127,127,127,0.08)">{{ s.latex }}</pre>
                  </details>
                }

                <!-- Concept extraction and storyboarding for this one section -->
                <div class="mt-4 border-t pt-3" style="border-color: var(--el-border)">
                  <div class="flex flex-wrap items-center gap-3">
                    <button
                      type="button"
                      class="rounded px-2.5 py-1 text-xs font-medium"
                      style="background: rgba(99,102,241,0.15); color: #6366f1"
                      [disabled]="analysing() !== null"
                      (click)="analyse(s.id)"
                    >
                      {{ analysing() === s.id ? 'Analysing…' : 'Find concepts' }}
                    </button>

                    @if (analysing() === s.id) {
                      <span class="text-xs" style="color: var(--el-muted)">
                        Several model calls — usually 20&ndash;90 seconds.
                      </span>
                    } @else if (timings()[s.id]; as ms) {
                      <span class="text-xs" style="color: var(--el-muted)">
                        {{ (ms / 1000).toFixed(0) }}s
                      </span>
                    }
                  </div>

                  @if (conceptsFor(s.id); as list) {
                    @if (list.length > 0) {
                      <div class="mt-3 space-y-3">
                        @for (c of list; track c.id) {
                          <pv-concept-card [concept]="c" />
                        }
                      </div>
                    } @else if (analysed()[s.id]) {
                      <p class="mt-2 text-xs" style="color: var(--el-muted)">
                        Nothing here worth animating — which is the right answer for most sections.
                      </p>
                    }
                  }
                </div>
              </li>
            }
          </ol>
        } @else if (!busy()) {
          <p class="card rounded-lg p-6 text-center text-sm" style="color: var(--el-muted)">
            No sections were extracted.
          </p>
        }
      } @else if (!error()) {
        <p class="mt-6 text-sm" style="color: var(--el-muted)">Loading…</p>
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

  /** Section id currently being analysed, or null. One at a time — the model is the bottleneck. */
  readonly analysing = signal<string | null>(null);
  readonly concepts = signal<Record<string, ConceptView[]>>({});
  readonly analysed = signal<Record<string, boolean>>({});
  readonly timings = signal<Record<string, number>>({});

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

    // Concepts already in the database cost minutes of model time to produce;
    // show them on load rather than making the user re-run analysis after a refresh.
    afterNextRender(() => this.loadExistingConcepts());
  }

  private loadExistingConcepts(): void {
    this.api.getConcepts(this.id()).subscribe({
      next: (all) => {
        const bySection: Record<string, ConceptView[]> = {};
        for (const concept of all) {
          (bySection[concept.sectionId] ??= []).push(concept);
        }
        this.concepts.set(bySection);
        this.analysed.update((map) => {
          const next = { ...map };
          for (const sectionId of Object.keys(bySection)) {
            next[sectionId] = true;
          }
          return next;
        });
      },
      // A failure here is not worth an error banner — the button still works.
      error: () => undefined,
    });
  }

  conceptsFor(sectionId: string): ConceptView[] | null {
    return this.concepts()[sectionId] ?? null;
  }

  analyse(sectionId: string): void {
    if (this.analysing() !== null) {
      return;
    }
    this.analysing.set(sectionId);
    this.error.set(null);

    this.api.analyzeSection(this.id(), sectionId).subscribe({
      next: (result) => {
        this.concepts.update((map) => ({ ...map, [sectionId]: result.concepts }));
        this.analysed.update((map) => ({ ...map, [sectionId]: true }));
        this.timings.update((map) => ({ ...map, [sectionId]: result.elapsedMs }));
        this.analysing.set(null);
      },
      error: (e: Error) => {
        this.error.set(e.message);
        this.analysing.set(null);
      },
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
