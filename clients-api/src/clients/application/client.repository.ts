import { Client } from '../domain/client';

export abstract class ClientRepository {
  abstract findById(clientId: string, signal: AbortSignal): Promise<Client | null>;
}
