import { INestApplication } from '@nestjs/common';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import request from 'supertest';
import { createApp } from './helpers';

const CONTRACTS = join(__dirname, '..', '..', 'contracts');
const describeIfContracts = existsSync(CONTRACTS) ? describe : describe.skip;

const readJson = (relative: string): Record<string, unknown> =>
  JSON.parse(readFileSync(join(CONTRACTS, relative), 'utf8')) as Record<string, unknown>;

describeIfContracts('Contrato de clients-api', () => {
  let app: INestApplication;
  beforeAll(async () => (app = await createApp({ faultInjectionEnabled: true })));
  afterAll(() => app.close());

  it('la respuesta de CLI-99821 coincide con el ejemplo del contrato', async () => {
    const res = await request(app.getHttpServer()).get('/clients/CLI-99821').expect(200);
    expect(res.body).toEqual(readJson('http/examples/client-cli-99821.json'));
  });

  it('todas las respuestas de error cumplen error.schema.json', async () => {
    const schema = readJson('http/error.schema.json') as {
      required: string[];
      properties: { code: { pattern: string } };
    };
    const codePattern = new RegExp(schema.properties.code.pattern);
    const cases: Array<[method: 'get' | 'post', path: string, fault?: string]> = [
      ['get', '/clients/CLI-00000'],
      ['get', '/clients/CLI_1'],
      ['get', '/clients/CLI-99821', '429'],
      ['get', '/clients/CLI-99821', '503'],
      ['post', '/clients/CLI-99821'],
      ['get', '/nope'],
    ];
    for (const [method, path, fault] of cases) {
      const req = request(app.getHttpServer())[method](path);
      const res = await (fault === undefined ? req : req.set('X-Fault', fault));
      for (const field of schema.required) {
        expect(res.body).toHaveProperty(field);
      }
      expect(res.body.code).toMatch(codePattern);
      expect(res.body.status).toBe(res.status);
    }
  });
});
