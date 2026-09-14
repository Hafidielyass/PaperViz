import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
  viewChildren,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { Explainer, SegmentView, isInProgress } from '../../core/api.models';
import { KatexDirective } from './katex.directive';

/**
 * The explainer reader: short rewritten text with an animation beside each part.
 *
 * This is the product. The section-by-section view at /papers/:id is the debug
 * surface for checking the pipeline, and shows the paper verbatim; this shows
 * only what the pipeline wrote.
 */
@Component({
  selector: 'pv-reader',
  imports: [RouterLink, KatexDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      :host {
        display: block;
      }
      .cover {
        min-height: 78vh;
      }
      .card {
        border: 1px solid var(--pv-border);
        background: var(--pv-surface);
      }
      .body-text {
        line-height: 1.75;
        font-size: 1.02rem;
      }
      .body-text p + p {
        margin-top: 1rem;
      }
      .eq {
        overflow-x: auto;
        overflow-y: hidden;
        padding: 0.25rem 0;
      }
      .rail {
        position: sticky;
        bottom: 1rem;
      }
      .seg {
        scroll-margin-top: 2rem;
      }
      @media (prefers-reduced-motion: no-preference) {
        .seg {
          transition: opacity 400ms ease;
        }
      }
    `,
  ],
  template: `
    <div class="mx-auto max-w-3xl px-6">
      <nav class="flex items-center gap-4 pt-6 text-xs">
        <a routerLink="/" class="underline" style="color: var(--pv-muted)">&larr; library</a>
        <a
          [routerLink]="['/papers', id()]"
          class="underline"
          style="color: var(--pv-muted)"
          >source sections</a
        >
      </nav>

      @if (error(); as e) {
        <div class="mt-6 rounded-lg border border-red-400/60 bg-red-500/10 p-4 text-sm text-red-500">
          {{ e }}
        </div>
      }

      @if (data(); as d) {
        <!-- Cover -->
        <header class="cover flex flex-col justify-center py-16">
          <p class="mb-3 text-xs tracking-widest uppercase" style="color: var(--pv-muted)">
            Research paper
          </p>
          <h1 class="text-4xl leading-tight font-semibold tracking-tight">
            {{ d.paper.title || d.paper.originalFilename || 'Untitled' }}
          </h1>
          @if (d.paper.authors) {
            <p class="mt-3 text-sm" style="color: var(--pv-muted)">{{ d.paper.authors }}</p>
          }

          @if (d.segments.length > 0) {
            <p class="mt-12 text-center text-xs tracking-widest uppercase" style="color: var(--pv-muted)">
              Scroll to begin reading
            </p>
          }
        </header>

        @if (d.segments.length === 0) {
          <div class="card mb-16 rounded-xl p-8">
            @if (busy()) {
              <!-- Progress per stage: the whole build is several minutes, and a
                   blank page for that long reads as a hang. -->
              <ol class="mx-auto max-w-sm space-y-3 text-sm">
                @for (step of pipeline(); track step.label) {
                  <li class="flex items-center gap-3">
                    <span
                      class="inline-block h-2 w-2 shrink-0 rounded-full"
                      [style.background]="
                        step.state === 'done'
                          ? '#10b981'
                          : step.state === 'active'
                            ? '#f59e0b'
                            : 'var(--pv-border)'
                      "
                    ></span>
                    <span [style.color]="step.state === 'pending' ? 'var(--pv-muted)' : 'inherit'">
                      {{ step.label }}
                    </span>
                    @if (step.state === 'active') {
                      <span class="text-xs" style="color: var(--pv-muted)">working…</span>
                    }
                  </li>
                }
              </ol>
              @if (d.paper.statusDetail) {
                <p class="mt-5 text-center text-xs" style="color: var(--pv-muted)">
                  {{ d.paper.statusDetail }}
                </p>
              }
            } @else {
              <p class="mb-4 text-center text-sm" style="color: var(--pv-muted)">
                No explainer has been written for this paper yet.
              </p>
              <button
                type="button"
                class="rounded px-3 py-1.5 text-sm font-medium"
                style="background: rgba(99,102,241,0.15); color: #6366f1"
                (click)="write()"
              >
                Write the explainer
              </button>
            }
          </div>
        }

        <!-- Segments -->
        @for (s of d.segments; track s.id; let i = $index) {
          <section #seg class="seg card mb-10 rounded-xl p-8" [attr.data-index]="i">
            <p class="mb-3 text-xs tracking-widest uppercase" style="color: var(--pv-muted)">
              {{ pad(i + 1) }} &nbsp;|&nbsp; Section {{ i + 1 }} of {{ d.segments.length }}
            </p>

            <h2 class="text-2xl font-semibold tracking-tight">{{ s.title }}</h2>

            <div class="body-text mt-4">
              @for (para of paragraphs(s); track $index) {
                <p>{{ para }}</p>
              }
            </div>

            @if (s.latex) {
              <div class="eq my-6 text-center" [pvKatex]="s.latex"></div>
            }

            @if (s.keyTakeaway) {
              <p class="mt-5 text-sm">
                <span class="font-semibold">Key takeaway:</span>
                <span style="color: var(--pv-muted)"> {{ s.keyTakeaway }}</span>
              </p>
            }

            <!-- Animation -->
            <div class="mt-6">
              <p class="mb-2 text-xs tracking-widest uppercase" style="color: var(--pv-muted)">
                Visualization
              </p>

              @if (videoFor(s); as url) {
                <video
                  [src]="url"
                  controls
                  playsinline
                  preload="metadata"
                  class="w-full rounded-lg"
                  style="background: #000"
                ></video>
              } @else if (rendering() === s.id) {
                <div class="rounded-lg p-6 text-center text-sm" style="background: rgba(127,127,127,0.08); color: var(--pv-muted)">
                  Rendering the animation — a minute or two.
                </div>
              } @else if (hasStoryboard(s)) {
                <button
                  type="button"
                  class="rounded px-3 py-1.5 text-sm font-medium"
                  style="background: rgba(16,185,129,0.15); color: #10b981"
                  [disabled]="rendering() !== null"
                  (click)="render(s.id)"
                >
                  Render animation
                </button>
              } @else {
                <p class="text-xs" style="color: var(--pv-muted)">
                  No animation for this part.
                </p>
              }
            </div>
          </section>
        }

        @if (d.segments.length > 0) {
          <div class="rail flex justify-center pb-10">
            <span
              class="rounded-full px-3 py-1 text-xs backdrop-blur"
              style="background: rgba(127,127,127,0.18); color: var(--pv-muted)"
            >
              {{ current() }} / {{ d.segments.length }}
            </span>
          </div>

          <footer class="pb-20 text-center">
            <p class="text-xs tracking-widest uppercase" style="color: var(--pv-muted)">
              End of paper
            </p>
            <div class="mt-3 flex justify-center gap-4 text-xs">
              <button type="button" class="underline" style="color: var(--pv-muted)" (click)="toTop()">
                Back to top
              </button>
              <a [href]="pdfUrl()" target="_blank" rel="noopener" class="underline" style="color: var(--pv-muted)">
                original PDF
              </a>
              <button type="button" class="underline" style="color: var(--pv-muted)" (click)="write()">
                rewrite
              </button>
            </div>
          </footer>
        }
      } @else if (!error()) {
        <p class="py-20 text-sm" style="color: var(--pv-muted)">Loading…</p>
      }
    </div>
  `,
})
export class ReaderPage {
  private readonly api = inject(ApiService);

  readonly id = input.required<string>();

  readonly data = signal<Explainer | null>(null);
  readonly error = signal<string | null>(null);
  readonly rendering = signal<string | null>(null);
  readonly videos = signal<Record<string, string>>({});
  readonly current = signal(1);

  private readonly segmentEls = viewChildren<ElementRef<HTMLElement>>('seg');

  readonly busy = computed(() => {
    const d = this.data();
    return d ? isInProgress(d.paper.status) : false;
  });

  /** The stages of a build, and where this paper has got to. */
  readonly pipeline = computed(() => {
    const status = this.data()?.paper.status;
    const order: Record<string, number> = {
      UPLOADED: 0, PARSING: 0, PARSED: 1, ANALYZING: 1, ANALYZED: 1,
      WRITING: 1, WRITTEN: 2, RENDERING: 2, READY: 3, FAILED: 3,
    };
    const reached = order[status ?? 'UPLOADED'] ?? 0;
    const labels = [
      'Reading the paper',
      'Choosing and writing the parts',
      'Rendering narrated animations',
    ];
    return labels.map((label, i) => ({
      label,
      state: i < reached ? 'done' : i === reached ? 'active' : 'pending',
    }));
  });

  constructor() {
    // Poll while the pipeline is still working, then settle.
    timer(0, 4000)
      .pipe(
        switchMap(() => this.api.getExplainer(this.id())),
        takeUntilDestroyed(),
      )
      .subscribe({
        next: (d) => {
          this.data.set(d);
          this.error.set(null);
        },
        error: (e: Error) => this.error.set(e.message),
      });

    afterNextRender(() => this.watchScroll());
  }

  /** Splits the written body on blank lines, the way the writer was told to format it. */
  paragraphs(segment: SegmentView): string[] {
    return (segment.body ?? '')
      .split(/\n\s*\n/)
      .map((p) => p.trim())
      .filter((p) => p.length > 0);
  }

  hasStoryboard(segment: SegmentView): boolean {
    const sb = segment.storyboard as unknown;
    return !!sb && typeof sb === 'object' && 'beats' in (sb as object);
  }

  /**
   * A segment's videoUrl is set by the server only when a render is READY, so
   * an already-rendered video appears on load without re-doing the work.
   * Locally rendered ones are layered on top.
   */
  videoFor(segment: SegmentView): string | null {
    return this.videos()[segment.id] ?? segment.videoUrl ?? null;
  }

  render(segmentId: string): void {
    this.rendering.set(segmentId);
    this.api.renderSegment(segmentId).subscribe({
      next: (result) => {
        this.rendering.set(null);
        if (result.ok && result.videoUrl) {
          this.videos.update((map) => ({ ...map, [segmentId]: result.videoUrl! }));
        } else {
          this.error.set(result.message ?? 'Rendering failed.');
        }
      },
      error: (e: Error) => {
        this.rendering.set(null);
        this.error.set(e.message);
      },
    });
  }

  write(): void {
    this.api.writeExplainer(this.id()).subscribe({
      error: (e: Error) => this.error.set(e.message),
    });
  }

  pdfUrl(): string {
    return this.api.pdfUrl(this.id());
  }

  toTop(): void {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  pad(n: number): string {
    return n < 10 ? `0${n}` : `${n}`;
  }

  /**
   * Drives the progress counter from whichever segment is nearest the middle of
   * the viewport. IntersectionObserver rather than a scroll handler so this
   * costs nothing on the scroll thread.
   */
  private watchScroll(): void {
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const index = Number((entry.target as HTMLElement).dataset['index'] ?? 0);
            this.current.set(index + 1);
          }
        }
      },
      { rootMargin: '-45% 0px -45% 0px', threshold: 0 },
    );

    // Segments arrive asynchronously, so re-observe whenever the list changes.
    const attach = () => {
      observer.disconnect();
      for (const ref of this.segmentEls()) {
        observer.observe(ref.nativeElement);
      }
    };
    attach();
    const poll = setInterval(attach, 2000);
    setTimeout(() => clearInterval(poll), 30000);
  }
}
