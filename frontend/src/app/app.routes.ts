import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/library/library.page').then((m) => m.LibraryPage),
  },
  {
    // The explainer is the product; the verbatim section view is the debug surface.
    path: 'read/:id',
    loadComponent: () => import('./pages/reader/reader.page').then((m) => m.ReaderPage),
  },
  {
    path: 'papers/:id',
    loadComponent: () => import('./pages/paper/paper.page').then((m) => m.PaperPage),
  },
  {
    path: 'health',
    loadComponent: () => import('./pages/health/health.page').then((m) => m.HealthPage),
  },
  { path: '**', redirectTo: '' },
];
