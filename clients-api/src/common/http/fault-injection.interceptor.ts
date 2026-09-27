import { CallHandler, ExecutionContext, Inject, Injectable, NestInterceptor } from '@nestjs/common';
import { Request } from 'express';
import { Observable, throwError } from 'rxjs';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { ApiError, ErrorCode } from './api-error';
import { requestContext } from './request-context';

const RESERVED_PREFIX = 'CLI-FAIL-';
const HEADER_FAULTS = new Set(['429', '500', '502', '503', 'timeout']);

type Fault = { readonly kind: 'hang' } | { readonly kind: 'error'; readonly error: () => ApiError };

const FAULTS: Readonly<Record<string, Fault>> = {
  '400': { kind: 'error', error: () => new ApiError(400, ErrorCode.INVALID_PARAMETER, 'Fault injected: bad request') },
  '429': {
    kind: 'error',
    error: () => new ApiError(429, ErrorCode.RATE_LIMITED, 'Fault injected: too many requests', [], { 'Retry-After': '1' }),
  },
  '500': { kind: 'error', error: () => new ApiError(500, ErrorCode.INTERNAL_ERROR, 'Fault injected: internal error') },
  '502': { kind: 'error', error: () => new ApiError(502, ErrorCode.BAD_GATEWAY, 'Fault injected: bad gateway') },
  '503': {
    kind: 'error',
    error: () => new ApiError(503, ErrorCode.SERVICE_UNAVAILABLE, 'Fault injected: service unavailable'),
  },
  timeout: { kind: 'hang' },
};

export function lookupFault(header: string | undefined, clientId: string | undefined): Fault | undefined {
  if (header !== undefined && HEADER_FAULTS.has(header)) {
    return FAULTS[header];
  }
  if (clientId?.startsWith(RESERVED_PREFIX)) {
    return FAULTS[clientId.slice(RESERVED_PREFIX.length).toLowerCase()];
  }
  return undefined;
}

@Injectable()
export class FaultInjectionInterceptor implements NestInterceptor {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    if (!this.config.faultInjectionEnabled) {
      return next.handle();
    }
    const req = context.switchToHttp().getRequest<Request>();
    const clientId = req.params['clientId'];
    const fault = lookupFault(req.header('x-fault'), typeof clientId === 'string' ? clientId : undefined);
    if (fault === undefined) {
      return next.handle();
    }
    if (fault.kind === 'error') {
      return throwError(fault.error);
    }
    // "timeout": no responde hasta que vence el deadline o el cliente cancela.
    return untilAborted(requestContext(req).signal);
  }
}

function untilAborted(signal: AbortSignal): Observable<never> {
  return new Observable<never>((subscriber) => {
    const onAbort = (): void => subscriber.error(signal.reason);
    if (signal.aborted) {
      onAbort();
      return undefined;
    }
    signal.addEventListener('abort', onAbort, { once: true });
    return () => signal.removeEventListener('abort', onAbort);
  });
}
