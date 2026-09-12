import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./pages/health/health.page').then((m) => m.HealthPage),
  },
  { path: '**', redirectTo: '' },
];
