import { Inject, Injectable, Logger, NestMiddleware } from '@nestjs/common';
import { NextFunction, Request, Response } from 'express';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { attachRequestContext, ClientDisconnectedError, RequestTimeoutError } from './request-context';
import { resolveTraceId } from './trace';

@Injectable()
export class RequestContextMiddleware implements NestMiddleware {
  private readonly logger = new Logger('HTTP');

  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  use(req: Request, res: Response, next: NextFunction): void {
    const start = process.hrtime.bigint();
    const traceId = resolveTraceId(req.header('traceparent'), req.header('x-request-id'));
    res.setHeader('X-Trace-Id', traceId);

    const controller = new AbortController();
    const deadline = setTimeout(() => controller.abort(new RequestTimeoutError()), this.config.requestTimeoutMs);
    deadline.unref();

    // 'close' se emite siempre: tras enviar la respuesta o si la conexión se corta antes.
    res.on('close', () => {
      clearTimeout(deadline);
      const completed = res.writableFinished;
      if (!completed) {
        controller.abort(new ClientDisconnectedError());
      }
      this.logAccess(req, completed ? res.statusCode : 499, start, traceId);
    });

    attachRequestContext(req, { traceId, signal: controller.signal });
    next();
  }

  private logAccess(req: Request, status: number, start: bigint, traceId: string): void {
    const entry = {
      msg: 'http_request',
      method: req.method,
      path: req.path,
      status,
      durationMs: Number((process.hrtime.bigint() - start) / 1_000_000n),
      traceId,
    };
    if (req.path === '/health') {
      // Docker lo consulta cada pocos segundos: a nivel info solo sería ruido.
      this.logger.debug(entry);
    } else if (status >= 500) {
      this.logger.error(entry);
    } else {
      this.logger.log(entry);
    }
  }
}
