/**
 * Errores de dominio. No conocen HTTP: la capa de transporte los traduce a códigos de estado.
 */
export class ClientNotFoundError extends Error {
  constructor(readonly clientId: string) {
    super(`Client ${clientId} not found`);
    this.name = 'ClientNotFoundError';
  }
}
