import { INestApplication } from '@nestjs/common';
import { Server } from 'node:http';
import { AddressInfo } from 'node:net';
import request, { Response } from 'supertest';
import { ClientRepository } from '../src/clients/application/client.repository';
import { Client } from '../src/clients/domain/client';
import { ClientDisconnectedError, RequestTimeoutError } from '../src/common/http/request-context';
import { ShutdownState } from '../src/health/shutdown-state';
import { createApp } from './helpers';

function expectErrorContract(res: Response, status: number, code: string): void {
  expect(res.status).toBe(status);
  expect(res.body).toMatchObject({ code, status });
  expect(typeof res.body.message).toBe('string');
  expect(Date.parse(res.body.timestamp)).not.toBeNaN();
  expect(res.body.traceId).toBe(res.headers['x-trace-id']);
}

/** Repositorio que bloquea hasta que la señal se aborta y registra el motivo. */
class BlockingRepository extends ClientRepository {
  readonly observed: Promise<unknown>;
  private resolveObserved!: (reason: unknown) => void;
  constructor() {
    super();
    this.observed = new Promise((resolve) => (this.resolveObserved = resolve));
  }
  findById(_clientId: string, signal: AbortSignal): Promise<Client | null> {
    return new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => {
        this.resolveObserved(signal.reason);
        reject(signal.reason);
      });
    });
  }
}

describe('GET /clients/:clientId', () => {
  let app: INestApplication;
  beforeAll(async () => (app = await createApp()));
  afterAll(() => app.close());

  it('200 con el contrato del cliente', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-99821').expect(200);
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.body).toEqual({
      clientId: 'CLI-99821',
      name: 'Distribuidora Central',
      status: 'ACTIVE',
      segment: 'WHOLESALE',
      taxRegime: 'GENERAL',
      market: 'MX',
    });
    expect(res.headers['x-trace-id']).toBeDefined();
    expect(res.headers['x-powered-by']).toBeUndefined();
  });

  it('200 para un cliente BLOCKED: es un dato de negocio, no un error', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-30002').expect(200);
    expect(res.body.status).toBe('BLOCKED');
  });

  it('404 CLIENT_NOT_FOUND si no existe', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-00000');
    expectErrorContract(res, 404, 'CLIENT_NOT_FOUND');
  });

  it.each(['CLI_1', 'CLI%201', 'A'.repeat(65)])('400 INVALID_PARAMETER con detalle para "%s"', async (id) => {
    const res = await request(app.getHttpServer()).get(`/clients/${id}`);
    expectErrorContract(res, 400, 'INVALID_PARAMETER');
    expect(res.body.details).toEqual([{ field: 'clientId', issue: expect.any(String) }]);
  });

  it('405 con cabecera Allow para un método no soportado', async () => {
    const res = await request(app.getHttpServer()).post('/clients/CLI-99821');
    expectErrorContract(res, 405, 'METHOD_NOT_ALLOWED');
    expect(res.headers['allow']).toBe('GET, HEAD');
  });

  it.each(['/', '/clients', '/clients/CLI-1/extra', '/products/PRD-001'])('404 ROUTE_NOT_FOUND para %s', async (path) => {
    const res = await request(app.getHttpServer()).get(path);
    expectErrorContract(res, 404, 'ROUTE_NOT_FOUND');
  });

  it('propaga el traceId de traceparent', async () => {
    const traceId = '4bf92f3577b34da6a3ce929d0e0e4736';
    const res = await request(app.getHttpServer())
      .get('/clients/CLI-00000')
      .set('traceparent', `00-${traceId}-00f067aa0ba902b7-01`);
    expect(res.body.traceId).toBe(traceId);
    expect(res.headers['x-trace-id']).toBe(traceId);
  });
});

describe('Cancelación y deadline', () => {
  it('el deadline llega al repositorio y responde 503 REQUEST_TIMEOUT', async () => {
    const repository = new BlockingRepository();
    const app = await createApp({ requestTimeoutMs: 50 }, repository);
    try {
      const res = await request(app.getHttpServer()).get('/clients/CLI-99821');
      expectErrorContract(res, 503, 'REQUEST_TIMEOUT');
      await expect(repository.observed).resolves.toBeInstanceOf(RequestTimeoutError);
    } finally {
      await app.close();
    }
  });

  it('si el cliente se desconecta, el repositorio recibe la cancelación', async () => {
    const repository = new BlockingRepository();
    const app = await createApp({ requestTimeoutMs: 60_000 }, repository);
    await app.listen(0);
    try {
      const { port } = (app.getHttpServer() as Server).address() as AddressInfo;
      const controller = new AbortController();
      const pending = fetch(`http://127.0.0.1:${port}/clients/CLI-99821`, { signal: controller.signal }).catch(
        () => undefined,
      );
      await new Promise((resolve) => setTimeout(resolve, 50));
      controller.abort(); // el cliente cierra la conexión
      await pending;
      await expect(repository.observed).resolves.toBeInstanceOf(ClientDisconnectedError);
    } finally {
      await app.close();
    }
  });
});

describe('Errores internos', () => {
  it('500 INTERNAL_ERROR sin filtrar detalles al cliente', async () => {
    const failing = new (class extends ClientRepository {
      findById(): Promise<Client | null> {
        return Promise.reject(new Error('connection refused to db-primary:5432'));
      }
    })();
    const app = await createApp({}, failing);
    try {
      const res = await request(app.getHttpServer()).get('/clients/CLI-99821');
      expectErrorContract(res, 500, 'INTERNAL_ERROR');
      expect(JSON.stringify(res.body)).not.toContain('db-primary');
    } finally {
      await app.close();
    }
  });
});

describe('GET /health', () => {
  it('UP y luego DOWN (503) al iniciar el apagado', async () => {
    const app = await createApp();
    try {
      await request(app.getHttpServer()).get('/health').expect(200, { status: 'UP' });
      app.get(ShutdownState).markShuttingDown();
      await request(app.getHttpServer()).get('/health').expect(503, { status: 'DOWN' });
    } finally {
      await app.close();
    }
  });
});

describe('Inyección de fallos', () => {
  describe('habilitada', () => {
    let app: INestApplication;
    beforeAll(async () => (app = await createApp({ faultInjectionEnabled: true, requestTimeoutMs: 50 })));
    afterAll(() => app.close());

    it.each([
      ['429', 429, 'RATE_LIMITED'],
      ['500', 500, 'INTERNAL_ERROR'],
      ['502', 502, 'BAD_GATEWAY'],
      ['503', 503, 'SERVICE_UNAVAILABLE'],
      ['timeout', 503, 'REQUEST_TIMEOUT'],
    ])('X-Fault: %s → %d %s', async (fault, status, code) => {
      const res = await request(app.getHttpServer()).get('/clients/CLI-99821').set('X-Fault', fault);
      expectErrorContract(res, status, code);
    });

    it('429 incluye Retry-After', async () => {
      const res = await request(app.getHttpServer()).get('/clients/CLI-FAIL-429');
      expect(res.status).toBe(429);
      expect(res.headers['retry-after']).toBe('1');
    });

    it.each([
      ['CLI-FAIL-400', 400],
      ['CLI-FAIL-503', 503],
      ['CLI-FAIL-TIMEOUT', 503],
      ['CLI-FAIL-XYZ', 404],
    ])('ID reservado %s → %d', async (id, status) => {
      await request(app.getHttpServer()).get(`/clients/${id}`).expect(status);
    });

    it('/health nunca falla por inyección', async () => {
      await request(app.getHttpServer()).get('/health').set('X-Fault', '503').expect(200);
    });
  });

  describe('deshabilitada (por defecto)', () => {
    let app: INestApplication;
    beforeAll(async () => (app = await createApp({ faultInjectionEnabled: false })));
    afterAll(() => app.close());

    it('ignora el header X-Fault', async () => {
      await request(app.getHttpServer()).get('/clients/CLI-99821').set('X-Fault', '503').expect(200);
    });

    it('trata un ID reservado como cliente inexistente', async () => {
      await request(app.getHttpServer()).get('/clients/CLI-FAIL-503').expect(404);
    });
  });
});
