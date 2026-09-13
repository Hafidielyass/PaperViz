import { Directive, ElementRef, effect, inject, input } from '@angular/core';
import katex from 'katex';

/**
 * Renders a LaTeX string into the host element.
 *
 * throwOnError is off deliberately: a paper can contain a macro KaTeX does not
 * know, and a half-rendered formula in red is far better than an exception that
 * blanks the whole segment.
 */
@Directive({
  selector: '[pvKatex]',
})
export class KatexDirective {
  private readonly host = inject(ElementRef<HTMLElement>);

  readonly pvKatex = input.required<string | null>();
  readonly displayMode = input(true);

  constructor() {
    effect(() => {
      const latex = this.pvKatex();
      const element = this.host.nativeElement as HTMLElement;

      if (!latex || !latex.trim()) {
        element.innerHTML = '';
        return;
      }

      try {
        katex.render(latex, element, {
          displayMode: this.displayMode(),
          throwOnError: false,
          errorColor: '#f87171',
          output: 'html',
        });
      } catch {
        // KaTeX can still throw on malformed input despite throwOnError.
        element.textContent = latex;
      }
    });
  }
}
