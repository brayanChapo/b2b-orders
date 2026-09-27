
export const MARKETS = ['MX', 'CO', 'PE'] as const;
export type Market = (typeof MARKETS)[number];

export const CLIENT_STATUSES = ['ACTIVE', 'BLOCKED'] as const;
export type ClientStatus = (typeof CLIENT_STATUSES)[number];

export const SEGMENTS = ['WHOLESALE', 'RETAIL'] as const;
export type Segment = (typeof SEGMENTS)[number];

export const TAX_REGIMES = ['GENERAL', 'SIMPLIFIED', 'EXEMPT'] as const;
export type TaxRegime = (typeof TAX_REGIMES)[number];

/** Formato del identificador según el contrato: 1..64 caracteres alfanuméricos o guiones. */
export const CLIENT_ID_PATTERN = /^[A-Za-z0-9-]{1,64}$/;

export function isValidClientId(value: string): boolean {
  return CLIENT_ID_PATTERN.test(value);
}

export interface Client {
  readonly clientId: string;
  readonly name: string;
  readonly status: ClientStatus;
  readonly segment: Segment;
  readonly taxRegime: TaxRegime;
  readonly market: Market;
}
