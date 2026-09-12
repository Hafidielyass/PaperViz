/** Mirrors the JSON returned by GET /api/health on the Spring Boot side. */
export interface DependencyHealth {
  status: 'UP' | 'DOWN';
  detail?: string;
  error?: string;
  latencyMs: number;
}

export interface SystemHealth {
  service: string;
  timestamp: string;
  status: 'UP' | 'DEGRADED';
  dependencies: Record<string, DependencyHealth>;
}
