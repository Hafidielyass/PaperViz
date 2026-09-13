import {
  ChangeDetectionStrategy,
  Component,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { ApiService } from '../../core/api.service';
import { ConceptView, isRejection, isStoryboard } from '../../core/api.models';

/**
 * One extracted concept and, when there is one, its storyboard.
 *
 * A rejected storyboard is shown rather than hidden: the validator's complaints
 * are the most useful thing on the screen when the pipeline is being tuned.
 */
@Component({
  selector: 'pv-concept-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      .beat-rail {
        border-left: 2px solid var(--pv-border);
      }
      .mono {
        font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      }
    `,
  ],
  template: `
    <article class="rounded-lg border p-4" [style.border-color]="'var(--pv-border)'">
      <header class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h4 class="font-medium">{{ concept().title }}</h4>
        <span
          class="rounded px-1.5 py-0.5 text-xs"
          style="background: rgba(127,127,127,0.12); color: var(--pv-muted)"
        >
          {{ concept().conceptType }}
        </span>
        @if (board(); as b) {
          <span class="text-xs" style="color: var(--pv-muted)">
            {{ b.beats.length }} beats · {{ b.totalSeconds }}s
          </span>
        }
      </header>

      @if (concept().description) {
        <p class="mt-1.5 text-sm" style="color: var(--pv-muted)">{{ concept().description }}</p>
      }

      @if (rejection(); as r) {
        <div class="mt-3 rounded border border-amber-500/40 bg-amber-500/10 p-3 text-xs">
          <p class="mb-1 font-medium text-amber-600">
            No storyboard — rejected by the validation gate
          </p>
          <ul class="list-disc space-y-0.5 pl-4" style="color: var(--pv-muted)">
            @for (p of r.problems; track p) {
              <li>{{ p }}</li>
            }
          </ul>
        </div>
      }

      @if (board(); as b) {
        <p class="mt-3 text-sm italic" style="color: var(--pv-muted)">{{ b.summary }}</p>

        <ol class="beat-rail mt-3 space-y-3 pl-4">
          @for (beat of b.beats; track beat.order) {
            <li>
              <div class="flex items-baseline gap-2">
                <span class="mono text-xs" style="color: var(--pv-muted)">
                  {{ beat.order }} · {{ beat.seconds }}s
                </span>
              </div>
              <p class="mt-0.5 text-sm">{{ beat.visual }}</p>
              <p class="mt-1 text-sm" style="color: var(--pv-muted)">
                &ldquo;{{ beat.narration }}&rdquo;
              </p>
              @if (beat.latex) {
                <pre
                  class="mono mt-1 overflow-x-auto rounded p-2 text-xs"
                  style="background: rgba(127,127,127,0.08)"
                >{{ beat.latex }}</pre>
              }
            </li>
          }
        </ol>

        <details class="mt-3">
          <summary class="cursor-pointer text-xs" style="color: var(--pv-muted)">
            raw storyboard JSON
          </summary>
          <pre
            class="mono mt-2 max-h-80 overflow-auto rounded p-3 text-xs"
            style="background: rgba(127,127,127,0.08)"
          >{{ rawJson() }}</pre>
        </details>
      }

      @if (!board() && !rejection()) {
        <p class="mt-2 text-xs" style="color: var(--pv-muted)">
          Concept found, but no storyboard was generated for it.
        </p>
      }

      @if (board()) {
        <div class="mt-4 border-t pt-3" style="border-color: var(--pv-border)">
          @if (videoUrl(); as url) {
            <video
              [src]="url"
              controls
              playsinline
              preload="metadata"
              class="w-full rounded"
              style="background: #000; max-height: 420px"
            ></video>
            @if (renderMs(); as ms) {
              <p class="mt-1.5 text-xs" style="color: var(--pv-muted)">
                Rendered in {{ (ms / 1000).toFixed(0) }}s
              </p>
            }
          } @else {
            <div class="flex flex-wrap items-center gap-3">
              <button
                type="button"
                class="rounded px-2.5 py-1 text-xs font-medium"
                style="background: rgba(16,185,129,0.15); color: #10b981"
                [disabled]="rendering()"
                (click)="renderVideo()"
              >
                {{ rendering() ? 'Rendering…' : 'Render animation' }}
              </button>
              @if (rendering()) {
                <span class="text-xs" style="color: var(--pv-muted)">
                  Manim is drawing {{ board()!.beats.length }} beats — a minute or two.
                </span>
              }
            </div>
          }

          @if (renderError(); as err) {
            <div class="mt-2 rounded border border-red-400/50 bg-red-500/10 p-2.5 text-xs text-red-500">
              {{ err }}
            </div>
          }
        </div>
      }
    </article>
  `,
})
export class ConceptCard {
  private readonly api = inject(ApiService);

  readonly concept = input.required<ConceptView>();

  readonly rendering = signal(false);
  readonly videoUrl = signal<string | null>(null);
  readonly renderError = signal<string | null>(null);
  readonly renderMs = signal<number | null>(null);

  constructor() {
    // A video may already exist from an earlier session; rendering is minutes
    // of compute, so check before offering to do it again.
    afterNextRender(() => {
      this.api.renderStatus(this.concept().id).subscribe({
        next: (status) => {
          if (status.status === 'READY' && status.videoUrl) {
            this.videoUrl.set(status.videoUrl);
          } else if (status.status === 'FAILED' && status.lastError) {
            this.renderError.set(status.lastError);
          }
        },
        error: () => undefined,
      });
    });
  }

  renderVideo(): void {
    this.rendering.set(true);
    this.renderError.set(null);

    this.api.renderConcept(this.concept().id).subscribe({
      next: (result) => {
        this.rendering.set(false);
        this.renderMs.set(result.elapsedMs);
        if (result.ok && result.videoUrl) {
          this.videoUrl.set(result.videoUrl);
        } else {
          this.renderError.set(result.message ?? 'Rendering failed.');
        }
      },
      error: (e: Error) => {
        this.rendering.set(false);
        this.renderError.set(e.message);
      },
    });
  }

  readonly board = computed(() => {
    const sb = this.concept().storyboard;
    return isStoryboard(sb) ? sb : null;
  });

  readonly rejection = computed(() => {
    const sb = this.concept().storyboard;
    return isRejection(sb) ? sb : null;
  });

  readonly rawJson = computed(() => JSON.stringify(this.concept().storyboard, null, 2));
}
