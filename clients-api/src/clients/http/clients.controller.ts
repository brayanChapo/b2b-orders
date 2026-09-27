import { Controller, Get, Param, Req, UseInterceptors } from '@nestjs/common';
import { Request } from 'express';
import { FaultInjectionInterceptor } from '../../common/http/fault-injection.interceptor';
import { requestContext } from '../../common/http/request-context';
import { GetClientUseCase } from '../application/get-client.use-case';
import { ClientResponse, toClientResponse } from './client.response';
import { GetClientParams } from './get-client.params';

@Controller('clients')
@UseInterceptors(FaultInjectionInterceptor)
export class ClientsController {
  constructor(private readonly getClient: GetClientUseCase) {}

  @Get(':clientId')
  async findOne(@Param() params: GetClientParams, @Req() req: Request): Promise<ClientResponse> {
    const client = await this.getClient.execute(params.clientId, requestContext(req).signal);
    return toClientResponse(client);
  }
}
