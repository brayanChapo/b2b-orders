import { Injectable } from '@nestjs/common';
import { Client } from '../domain/client';
import { ClientNotFoundError } from '../domain/client.errors';
import { ClientRepository } from './client.repository';

@Injectable()
export class GetClientUseCase {
  constructor(private readonly clients: ClientRepository) {}

  async execute(clientId: string, signal: AbortSignal): Promise<Client> {
    signal.throwIfAborted();
    const client = await this.clients.findById(clientId, signal);
    if (client === null) {
      throw new ClientNotFoundError(clientId);
    }
    return client;
  }
}
