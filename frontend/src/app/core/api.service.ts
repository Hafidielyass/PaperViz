import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { SystemHealth } from './api.models';

/**
 * Single entry point for backend calls. The base path is relative so the same
 * build works behind nginx in compose and behind the dev-server proxy.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api';

  health(): Observable<SystemHealth> {
    return this.http.get<SystemHealth>(`${this.base}/health`);
  }
}
