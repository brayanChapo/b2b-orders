import { ArgumentsHost, Catch, ExceptionFilter, HttpException, Logger, NotFoundException } from '@nestjs/common';
import { Request, Response } from 'express';
import { ClientNotFoundError } from '../../clients/domain/client.errors';
import { ApiError, ErrorBody, ErrorCode } from './api-error';
import { ClientDisconnectedError, RequestTimeoutError, traceIdOf } from './request-context';


@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  private readonly logger = new Logger('Errors');

  catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const req = http.getRequest<Request>();
    const res = http.getResponse<Response>();

    // El cliente se fue o ya se respondió: no hay a quién (ni cómo) responder.
    if (exception instanceof ClientDisconnectedError || res.headersSent || res.destroyed) {
      return;
    }

    const error = toApiError(exception, req);
    if (error.status === 500) {
      this.logger.error({ msg: 'unexpected_error', traceId: traceIdOf(req), error: describe(exception) });
    }

    const body: ErrorBody = {
      code: error.code,
      message: error.message,
      status: error.status,
      traceId: traceIdOf(req),
      timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
      ...(error.details.length > 0 ? { details: error.details } : {}),
    };
    for (const [name, value] of Object.entries(error.headers)) {
      res.setHeader(name, value);
    }
    res.status(error.status).json(body);
  }
}

const KNOWN_ROUTES: readonly { readonly pattern: RegExp; readonly allow: string }[] = [
  { pattern: /^\/clients\/[^/]+\/?$/, allow: 'GET, HEAD' },
  { pattern: /^\/health\/?$/, allow: 'GET, HEAD' },
];

function toApiError(exception: unknown, req: Request): ApiError {
  if (exception instanceof ApiError) {
    return exception;
  }
  if (exception instanceof ClientNotFoundError) {
    return new ApiError(404, ErrorCode.CLIENT_NOT_FOUND, exception.message);
  }
  if (exception instanceof RequestTimeoutError) {
    return new ApiError(503, ErrorCode.REQUEST_TIMEOUT, 'Request timed out');
  }
  if (exception instanceof NotFoundException) {
    const route = KNOWN_ROUTES.find((r) => r.pattern.test(req.path));
    if (route !== undefined) {
      return new ApiError(405, ErrorCode.METHOD_NOT_ALLOWED, `Method ${req.method} not allowed`, [], {
        Allow: route.allow,
      });
    }
    return new ApiError(404, ErrorCode.ROUTE_NOT_FOUND, 'Route not found');
  }
  if (exception instanceof HttpException) {
    const status = exception.getStatus();
    return status >= 500
      ? new ApiError(500, ErrorCode.INTERNAL_ERROR, 'Internal server error')
      : new ApiError(status, ErrorCode.INVALID_PARAMETER, 'Invalid request');
  }
  return new ApiError(500, ErrorCode.INTERNAL_ERROR, 'Internal server error');
}

function describe(exception: unknown): string {
  return exception instanceof Error ? `${exception.name}: ${exception.message}` : String(exception);
}
