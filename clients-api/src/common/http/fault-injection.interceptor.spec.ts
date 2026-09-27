import { lookupFault } from './fault-injection.interceptor';

describe('lookupFault', () => {
  it.each(['429', '500', '502', '503', 'timeout'])('reconoce el header X-Fault: %s', (value) => {
    expect(lookupFault(value, 'CLI-99821')).toBeDefined();
  });

  it('ignora valores de header fuera del contrato', () => {
    expect(lookupFault('400', 'CLI-99821')).toBeUndefined();
    expect(lookupFault('boom', 'CLI-99821')).toBeUndefined();
  });

  it.each(['CLI-FAIL-400', 'CLI-FAIL-429', 'CLI-FAIL-503', 'CLI-FAIL-TIMEOUT'])('reconoce el ID reservado %s', (id) => {
    expect(lookupFault(undefined, id)).toBeDefined();
  });

  it('un ID reservado desconocido o un cliente normal no inyectan fallos', () => {
    expect(lookupFault(undefined, 'CLI-FAIL-XYZ')).toBeUndefined();
    expect(lookupFault(undefined, 'CLI-99821')).toBeUndefined();
  });
});
