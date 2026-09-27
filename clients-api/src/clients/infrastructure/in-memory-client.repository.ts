import { ClientRepository } from '../application/client.repository';
import { Client, isValidClientId } from '../domain/client';
import { CLIENT_SEED } from './client.seed';

export class InMemoryClientRepository extends ClientRepository {
  private readonly clients: ReadonlyMap<string, Client>;

  constructor(seed: readonly Client[] = CLIENT_SEED) {
    super();
    this.clients = InMemoryClientRepository.index(seed);
  }

  async findById(clientId: string, signal: AbortSignal): Promise<Client | null> {
    signal.throwIfAborted();
    return this.clients.get(clientId) ?? null;
  }

  get size(): number {
    return this.clients.size;
  }

  private static index(seed: readonly Client[]): ReadonlyMap<string, Client> {
    const map = new Map<string, Client>();
    for (const client of seed) {
      if (!isValidClientId(client.clientId)) {
        throw new Error(`Semilla inválida: clientId "${client.clientId}" no cumple el formato`);
      }
      if (map.has(client.clientId)) {
        throw new Error(`Semilla inválida: clientId "${client.clientId}" duplicado`);
      }
      map.set(client.clientId, Object.freeze({ ...client }));
    }
    return map;
  }
}
