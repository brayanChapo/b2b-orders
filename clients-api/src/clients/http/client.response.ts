import { Client } from '../domain/client';

export interface ClientResponse {
  readonly clientId: string;
  readonly name: string;
  readonly status: string;
  readonly segment: string;
  readonly taxRegime: string;
  readonly market: string;
}

export function toClientResponse(client: Client): ClientResponse {
  return {
    clientId: client.clientId,
    name: client.name,
    status: client.status,
    segment: client.segment,
    taxRegime: client.taxRegime,
    market: client.market,
  };
}
