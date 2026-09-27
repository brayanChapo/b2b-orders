import { Module } from '@nestjs/common';
import { ClientRepository } from './application/client.repository';
import { GetClientUseCase } from './application/get-client.use-case';
import { ClientsController } from './http/clients.controller';
import { InMemoryClientRepository } from './infrastructure/in-memory-client.repository';

@Module({
  controllers: [ClientsController],
  providers: [
    GetClientUseCase,
    { provide: ClientRepository, useFactory: () => new InMemoryClientRepository() },
  ],
})
export class ClientsModule {}
