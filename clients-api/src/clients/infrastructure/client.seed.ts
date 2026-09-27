import { Client } from '../domain/client';

export const CLIENT_SEED: readonly Client[] = [
  { clientId: 'CLI-99821', name: 'Distribuidora Central', status: 'ACTIVE', segment: 'WHOLESALE', taxRegime: 'GENERAL', market: 'MX' },
  { clientId: 'CLI-10002', name: 'Abarrotes del Norte', status: 'ACTIVE', segment: 'RETAIL', taxRegime: 'SIMPLIFIED', market: 'MX' },
  { clientId: 'CLI-10003', name: 'Comercializadora del Bajío', status: 'BLOCKED', segment: 'WHOLESALE', taxRegime: 'GENERAL', market: 'MX' },
  { clientId: 'CLI-20001', name: 'Mayorista Andina', status: 'ACTIVE', segment: 'WHOLESALE', taxRegime: 'GENERAL', market: 'CO' },
  { clientId: 'CLI-20002', name: 'Fundación Nutrir', status: 'ACTIVE', segment: 'RETAIL', taxRegime: 'EXEMPT', market: 'CO' },
  { clientId: 'CLI-20003', name: 'Tiendas La Esquina', status: 'ACTIVE', segment: 'RETAIL', taxRegime: 'SIMPLIFIED', market: 'CO' },
  { clientId: 'CLI-30001', name: 'Distribuidora del Pacífico', status: 'ACTIVE', segment: 'WHOLESALE', taxRegime: 'GENERAL', market: 'PE' },
  { clientId: 'CLI-30002', name: 'Bodega San Martín', status: 'BLOCKED', segment: 'RETAIL', taxRegime: 'GENERAL', market: 'PE' },
  { clientId: 'CLI-30003', name: 'Minimarket Lima Sur', status: 'ACTIVE', segment: 'RETAIL', taxRegime: 'EXEMPT', market: 'PE' },
];
