import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/library/library.page').then((m) => m.LibraryPage),
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
