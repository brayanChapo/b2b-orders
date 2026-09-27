import { Request } from 'express';

export interface RequestContext {
  readonly traceId: string;
  readonly signal: AbortSignal;
}

/** Motivo de aborto: se superó REQUEST_TIMEOUT. */
export class RequestTimeoutError extends Error {
  constructor() {
    super('Request timed out');
    this.name = 'RequestTimeoutError';
  }
}

/** Motivo de aborto: el cliente cerró la conexión antes de recibir la respuesta. */
export class ClientDisconnectedError extends Error {
  constructor() {
    super('Client disconnected');
    this.name = 'ClientDisconnectedError';
  }
}

const contexts = new WeakMap<Request, RequestContext>();

export function attachRequestContext(req: Request, context: RequestContext): void {
  contexts.set(req, context);
}

export function requestContext(req: Request): RequestContext {
  const context = contexts.get(req);
  if (context === undefined) {
    throw new Error('RequestContext no disponible: falta RequestContextMiddleware');
  }
  return context;
}

export function traceIdOf(req: Request): string | undefined {
  return contexts.get(req)?.traceId;
}
