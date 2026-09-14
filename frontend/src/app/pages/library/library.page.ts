import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, switchMap, timer } from 'rxjs';

import { ApiService } from '../../core/api.service';
import { PaperSummary, isInProgress } from '../../core/api.models';
import { LogoComponent } from '../../core/brand';

@Component({
  selector: 'el-library',
  imports: [RouterLink, DatePipe, LogoComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    `
      /* The drop target is the page's one interactive surface, so it gets the
         only dashed border in the design and nothing else competes with it. */
      .drop {
        border: 1px dashed var(--el-border-strong);
        background: var(--el-surface);
        transition:
          border-color 0.15s ease,
          background 0.15s ease;
      }
      .drop.active {
        border-color: var(--el-ink);
        background: var(--el-surface-2);
      }
      .tab {
        font-size: 0.8125rem;
        padding: 0.3rem 0.75rem;
        border-radius: 999px;
        color: var(--el-muted);
        cursor: pointer;
      }
      .tab.on {
        background: var(--el-surface-2);
        color: var(--el-ink);
      }
      .row {
        border-bottom: 1px solid var(--el-border);
      }
      .row:last-child {
        border-bottom: 0;
      }
      .field {
        border: 1px solid var(--el-border-strong);
        border-radius: var(--el-radius);
        background: #fff;
        padding: 0.6rem 0.85rem;
        font-size: 0.9rem;
        width: 100%;
      }
      .field:focus {
        outline: none;
        border-color: var(--el-ink);
      }
    `,
  ],
  template: `
    <!-- Top bar: the mark, and nothing that competes with it. -->
    <header class="border-b" style="border-color: var(--el-border)">
      <div class="mx-auto flex max-w-5xl items-center justify-between px-6 py-4">
        <el-logo [size]="26" />
        <a routerLink="/health" class="text-xs" style="color: var(--el-muted)">status</a>
      </div>
    </header>

    <section class="mx-auto max-w-2xl px-6 pt-20 pb-24">
      <div class="mb-10 text-center">
        <h1 class="text-[2rem] leading-tight">What would you like explained?</h1>
        <p class="mt-3 text-sm" style="color: var(--el-muted)">
          Drop in a paper and ELYRA turns it into a short visual explainer — written,
          illustrated and narrated.
        </p>
      </div>

      <!-- Source picker -->
      <div class="mb-3 flex justify-center gap-1">
        <span class="tab" [class.on]="tab() === 'file'" (click)="tab.set('file')">Upload a PDF</span>
        <span class="tab" [class.on]="tab() === 'url'" (click)="tab.set('url')">Paste a link</span>
      </div>

      @if (tab() === 'file') {
        <label
          class="drop flex cursor-pointer flex-col items-center justify-center rounded-xl px-6 py-14 text-center"
          [class.active]="dragging()"
          (dragover)="onDragOver($event)"
          (dragleave)="onDragLeave($event)"
          (drop)="onDrop($event)"
        >
          <input type="file" accept="application/pdf" class="hidden" (change)="onPick($event)" />
          @if (uploading()) {
            <p class="text-sm">Uploading {{ uploadingName() }}…</p>
          } @else {
            <p class="text-sm">Drop a PDF here, or click to choose one</p>
            <p class="mt-1.5 text-xs" style="color: var(--el-muted)">
              Your own copy, processed locally. Nothing is re-hosted.
            </p>
          }
        </label>
      } @else {
        <div class="drop rounded-xl px-6 py-10">
          <input
            class="field"
            type="url"
            placeholder="https://arxiv.org/abs/1706.03762"
            [value]="linkUrl()"
            (input)="linkUrl.set($any($event.target).value)"
            (keydown.enter)="onIngestUrl()"
          />
          <div class="mt-3 flex items-center justify-between gap-4">
            <p class="text-xs" style="color: var(--el-muted)">
              Open access only — arXiv, bioRxiv, PMC, OpenReview and others.
            </p>
            <button type="button" class="el-btn" [disabled]="linking()" (click)="onIngestUrl()">
              {{ linking() ? 'Fetching…' : 'Explain it' }}
            </button>
          </div>
        </div>
      }

      @if (error(); as e) {
        <p class="mt-4 rounded-lg px-4 py-3 text-sm"
           style="background: rgba(180,72,60,0.08); color: var(--el-bad)">{{ e }}</p>
      }

      <!-- Library -->
      @if (papers(); as list) {
        @if (list.length > 0) {
          <h2 class="mt-16 mb-1 text-xs tracking-widest uppercase" style="color: var(--el-muted)">
            Your papers
          </h2>
          <ul class="el-card overflow-hidden rounded-xl">
            @for (p of list; track p.id) {
              <li class="row flex items-center justify-between gap-4 px-5 py-4">
                <a [routerLink]="['/read', p.id]" class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium">
                    {{ p.title || p.originalFilename || 'Untitled' }}
                  </p>
                  <p class="mt-0.5 truncate text-xs" style="color: var(--el-muted)">
                    {{ p.createdAt | date: 'd MMM, HH:mm' }}
                    @if (p.sectionCount > 0) { · {{ p.sectionCount }} sections }
                  </p>
                </a>
                <span class="shrink-0 text-xs" [style.color]="statusFg(p)">{{ statusLabel(p) }}</span>
                <button type="button" class="shrink-0 text-xs" style="color: var(--el-muted)"
                        (click)="remove(p)">remove</button>
              </li>
            }
          </ul>
        }
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
  readonly tab = signal<'file' | 'url'>('file');
  readonly linkUrl = signal('');
  readonly linking = signal(false);

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

  onIngestUrl(): void {
    const url = this.linkUrl().trim();
    if (!url) {
      this.error.set('Paste a link first.');
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.linking.set(true);

    this.api.ingestUrl(url).subscribe({
      next: (res) => {
        this.linking.set(false);
        this.notice.set(res.message);
        void this.router.navigate(['/read', res.paperId]);
      },
      error: (e: Error) => {
        this.linking.set(false);
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

  statusFg(p: PaperSummary): string {
    if (p.status === 'FAILED') return 'var(--el-bad)';
    if (isInProgress(p.status)) return 'var(--el-warn)';
    return 'var(--el-muted)';
  }

  /** Plain words rather than enum names — READY means nothing to a reader. */
  statusLabel(p: PaperSummary): string {
    switch (p.status) {
      case 'FAILED':
        return 'failed';
      case 'READY':
      case 'WRITTEN':
        return 'ready';
      case 'UPLOADED':
      case 'PARSING':
        return 'reading';
      case 'PARSED':
      case 'ANALYZING':
      case 'ANALYZED':
      case 'WRITING':
        return 'writing';
      case 'RENDERING':
        return 'rendering';
      default:
        return String(p.status).toLowerCase();
    }
  }
}
