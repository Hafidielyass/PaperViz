import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

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
    </article>
  `,
})
export class ConceptCard {
  readonly concept = input.required<ConceptView>();

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
