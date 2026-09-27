import { resolveTraceId } from './trace';

describe('resolveTraceId', () => {
  const traceId = '4bf92f3577b34da6a3ce929d0e0e4736';

  it('usa el trace-id de traceparent', () => {
    expect(resolveTraceId(`00-${traceId}-00f067aa0ba902b7-01`, 'req-1')).toBe(traceId);
  });

  it('usa X-Request-Id si no hay traceparent válido', () => {
    expect(resolveTraceId('invalido', 'req-1')).toBe('req-1');
  });

  it('genera uno nuevo si X-Request-Id no es seguro', () => {
    const generated = resolveTraceId(undefined, '<script>');
    expect(generated).toMatch(/^[0-9a-f]{32}$/);
  });
});
