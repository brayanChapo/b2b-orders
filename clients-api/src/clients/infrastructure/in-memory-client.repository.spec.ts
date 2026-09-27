import { MARKETS } from '../domain/client';
import { CLIENT_SEED } from './client.seed';
import { InMemoryClientRepository } from './in-memory-client.repository';

const signal = (): AbortSignal => new AbortController().signal;

describe('InMemoryClientRepository', () => {
  const repository = new InMemoryClientRepository();

  it('encuentra un cliente existente', async () => {
    const client = await repository.findById('CLI-99821', signal());
    expect(client?.name).toBe('Distribuidora Central');
  });

  it('devuelve null si no existe', async () => {
    await expect(repository.findById('CLI-00000', signal())).resolves.toBeNull();
  });

  it('respeta la cancelación', async () => {
    const controller = new AbortController();
    const reason = new Error('timeout');
    controller.abort(reason);
    await expect(repository.findById('CLI-99821', controller.signal)).rejects.toBe(reason);
  });

  it('devuelve objetos inmutables: nadie puede alterar la semilla', async () => {
    const client = await repository.findById('CLI-99821', signal());
    expect(Object.isFrozen(client)).toBe(true);
  });

  it('rechaza una semilla con IDs inválidos o duplicados al construirse', () => {
    const base = CLIENT_SEED[0]!;
    expect(() => new InMemoryClientRepository([{ ...base, clientId: 'mal id' }])).toThrow(/formato/);
    expect(() => new InMemoryClientRepository([base, base])).toThrow(/duplicado/);
  });
});

describe('CLIENT_SEED', () => {
  // El enunciado exige al menos 6 clientes distribuidos entre los tres mercados.
  it('tiene al menos 6 clientes en los tres mercados', () => {
    expect(CLIENT_SEED.length).toBeGreaterThanOrEqual(6);
    for (const market of MARKETS) {
      expect(CLIENT_SEED.filter((c) => c.market === market).length).toBeGreaterThanOrEqual(2);
    }
  });

  it('cubre los casos que necesita order-processor', () => {
    expect(CLIENT_SEED.some((c) => c.status === 'BLOCKED')).toBe(true);
    expect(CLIENT_SEED.some((c) => c.taxRegime === 'EXEMPT')).toBe(true);
    expect(CLIENT_SEED.some((c) => c.segment === 'WHOLESALE')).toBe(true);
    expect(CLIENT_SEED.some((c) => c.segment === 'RETAIL')).toBe(true);
  });

  // Los fixtures de contracts/ asumen estos datos (contracts/README.md).
  it.each([
    { clientId: 'CLI-99821', market: 'MX', status: 'ACTIVE', segment: 'WHOLESALE', taxRegime: 'GENERAL' },
    { clientId: 'CLI-20002', market: 'CO', status: 'ACTIVE', taxRegime: 'EXEMPT' },
    { clientId: 'CLI-30002', market: 'PE', status: 'BLOCKED' },
  ])('coincide con los fixtures de contratos: $clientId', (expected) => {
    expect(CLIENT_SEED.find((c) => c.clientId === expected.clientId)).toMatchObject(expected);
  });
});
