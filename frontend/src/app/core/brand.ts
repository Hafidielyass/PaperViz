import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';

/** One place that knows what the product is called. */
export const BRAND = {
  name: 'ELYRA',
  tagline: '',
  // ?v=2 because the previous response was immutable-cached for a year; the
  // nginx location block for the logo now revalidates, so a v2 file will not
  // eed bumping again.
  logoSrc: 'elyra-logo.svg?v=2',
} as const;

/**
 * The ELYRA lockup.
 *
 * Renders the supplied logo when `public/elyra-logo.svg` is present and falls
 * back to the wordmark alone when it is not, so the app never shows a broken
 * image while the asset is still being dropped in. The fallback is deliberately
 * close to the real lockup — Montserrat Light with wide tracking — so the
 * difference is the valkyrie mark rather than the whole identity.
 */
@Component({
  selector: 'el-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="inline-flex items-center" [style.gap.px]="gap()">
      @if (!failed()) {
        <img
          [src]="src"
          [style.height.px]="size()"
          alt="ELYRA"
          class="block w-auto"
          (error)="failed.set(true)"
        />
      } @else {
        <!-- Mark unavailable: wordmark only, at the same optical weight. -->
        <span
          class="el-wordmark"
          [style.font-size.px]="size() * 0.52"
          [style.color]="'var(--el-ink)'"
          >{{ name }}</span
        >
      }
    </span>
  `,
})
export class LogoComponent {
  /** Lockup height in pixels. */
  readonly size = input(28);
  readonly gap = input(10);

  protected readonly src = BRAND.logoSrc;
  protected readonly name = BRAND.name;
  protected readonly failed = signal(false);
}
