import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { catchError, of, timer, switchMap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { ApiService } from '../../core/api.service';
import { SystemHealth } from '../../core/api.models';

@Component({
  selector: 'pv-health',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-3xl px-6 py-12">
      <header class="mb-8">
        <h1 class="text-3xl font-semibold tracking-tight">PaperViz</h1>
        <p class="mt-1 text-sm" style="color: var(--pv-muted)">
          Stage 1 — every service is containerised. This page proves the Angular container can
          reach the API container, and that the API can reach Postgres, Ollama, GROBID and the
          render service.
        </p>
      </header>

      @if (error()) {
        <div class="rounded-lg border border-red-400/60 bg-red-500/10 p-4 text-sm text-red-500">
          Cannot reach the backend: {{ error() }}
        </div>
      } @else if (health(); as h) {
        <div class="mb-4 flex items-center gap-3">
          <span
            class="inline-block h-3 w-3 rounded-full"
            [class.bg-emerald-500]="h.status === 'UP'"
            [class.bg-amber-500]="h.status !== 'UP'"
          ></span>
          <span class="font-medium">{{ h.service }}</span>
          <span class="text-sm" style="color: var(--pv-muted)">{{ h.status }}</span>
        </div>

        <ul class="divide-y rounded-lg border"
          style="border-color: var(--pv-border); background: var(--pv-surface)">
          @for (dep of dependencies(h); track dep.name) {
            <li class="flex items-start gap-4 p-4">
              <span
                class="mt-1.5 inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                [class.bg-emerald-500]="dep.status === 'UP'"
                [class.bg-red-500]="dep.status !== 'UP'"
              ></span>
              <div class="min-w-0 flex-1">
                <div class="flex items-baseline justify-between gap-4">
                  <span class="font-medium">{{ dep.name }}</span>
                  <span class="shrink-0 text-xs" style="color: var(--pv-muted)">{{ dep.latencyMs }} ms</span>
                </div>
                <p class="mt-1 truncate text-xs" style="color: var(--pv-muted)">
                  {{ dep.detail || dep.error }}
                </p>
              </div>
            </li>
          }
        </ul>
      } @else {
        <p class="text-sm" style="color: var(--pv-muted)">Checking services...</p>
      }
    </section>
  `,
})
export class HealthPage {
  private readonly api = inject(ApiService);

  readonly health = signal<SystemHealth | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    // Poll every 5s so a service that finishes booting shows up without a reload.
    timer(0, 5000)
      .pipe(
        switchMap(() =>
          this.api.health().pipe(catchError((e) => of({ __error: e.message ?? 'unknown' }))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((result) => {
        if (result && '__error' in result) {
          this.error.set((result as { __error: string }).__error);
        } else {
          this.error.set(null);
          this.health.set(result as SystemHealth);
        }
      });
  }

  dependencies(h: SystemHealth) {
    return Object.entries(h.dependencies).map(([name, value]) => ({ name, ...value }));
  }
}
