export const ErrorCode = {
  INVALID_PARAMETER: 'INVALID_PARAMETER',
  CLIENT_NOT_FOUND: 'CLIENT_NOT_FOUND',
  ROUTE_NOT_FOUND: 'ROUTE_NOT_FOUND',
  METHOD_NOT_ALLOWED: 'METHOD_NOT_ALLOWED',
  RATE_LIMITED: 'RATE_LIMITED',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  BAD_GATEWAY: 'BAD_GATEWAY',
  SERVICE_UNAVAILABLE: 'SERVICE_UNAVAILABLE',
  REQUEST_TIMEOUT: 'REQUEST_TIMEOUT',
} as const;
export type ErrorCode = (typeof ErrorCode)[keyof typeof ErrorCode];

export interface FieldIssue {
  readonly field: string;
  readonly issue: string;
}

export interface ErrorBody {
  readonly code: ErrorCode;
  readonly message: string;
  readonly status: number;
  readonly traceId?: string;
  readonly timestamp: string;
  readonly details?: readonly FieldIssue[];
}

/** Error ya expresado en términos del contrato HTTP. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: ErrorCode,
    message: string,
    readonly details: readonly FieldIssue[] = [],
    readonly headers: Readonly<Record<string, string>> = {},
  ) {
    super(message);
    this.name = 'ApiError';
  }
}
