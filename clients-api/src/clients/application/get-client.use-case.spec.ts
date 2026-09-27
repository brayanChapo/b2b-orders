import { Client } from '../domain/client';
import { ClientNotFoundError } from '../domain/client.errors';
import { ClientRepository } from './client.repository';
import { GetClientUseCase } from './get-client.use-case';

const CLIENT: Client = {
  clientId: 'CLI-1',
  name: 'Cliente de prueba',
  status: 'ACTIVE',
  segment: 'RETAIL',
  taxRegime: 'GENERAL',
  market: 'PE',
};

class FakeRepository extends ClientRepository {
  calls = 0;
  constructor(private readonly result: Client | null) {
    super();
  }
  findById(): Promise<Client | null> {
    this.calls++;
    return Promise.resolve(this.result);
  }
}

describe('GetClientUseCase', () => {
  it('devuelve el cliente cuando existe', async () => {
    const useCase = new GetClientUseCase(new FakeRepository(CLIENT));
    await expect(useCase.execute('CLI-1', new AbortController().signal)).resolves.toEqual(CLIENT);
  });

  it('lanza ClientNotFoundError cuando no existe', async () => {
    const useCase = new GetClientUseCase(new FakeRepository(null));
    await expect(useCase.execute('CLI-404', new AbortController().signal)).rejects.toBeInstanceOf(ClientNotFoundError);
  });

  it('no consulta el repositorio si la solicitud ya fue cancelada', async () => {
    const repository = new FakeRepository(CLIENT);
    const controller = new AbortController();
    const reason = new Error('cancelada');
    controller.abort(reason);

    await expect(new GetClientUseCase(repository).execute('CLI-1', controller.signal)).rejects.toBe(reason);
    expect(repository.calls).toBe(0);
  });
});
