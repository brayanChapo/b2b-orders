import { ConfigError, loadConfig, parseDuration } from './app-config';

describe('loadConfig', () => {
  it('usa valores por defecto seguros', () => {
    const config = loadConfig({});
    expect(config).toEqual({
      port: 8080,
      requestTimeoutMs: 5_000,
      shutdownTimeoutMs: 10_000,
      faultInjectionEnabled: false,
      logLevels: ['error', 'warn', 'log'],
    });
  });

  it('lee los valores del entorno', () => {
    const config = loadConfig({
      PORT: '9090',
      REQUEST_TIMEOUT: '2s',
      SHUTDOWN_TIMEOUT: '500ms',
      FAULT_INJECTION_ENABLED: 'true',
      LOG_LEVEL: 'DEBUG',
    });
    expect(config.port).toBe(9090);
    expect(config.requestTimeoutMs).toBe(2_000);
    expect(config.shutdownTimeoutMs).toBe(500);
    expect(config.faultInjectionEnabled).toBe(true);
    expect(config.logLevels).toContain('debug');
  });

  it.each([
    { PORT: 'abc' },
    { PORT: '70000' },
    { REQUEST_TIMEOUT: '5' },
    { REQUEST_TIMEOUT: '0s' },
    { SHUTDOWN_TIMEOUT: '-1s' },
    { FAULT_INJECTION_ENABLED: 'yes' },
    { LOG_LEVEL: 'verbose' },
  ])('rechaza valores inválidos: %p', (env) => {
    expect(() => loadConfig(env)).toThrow(ConfigError);
  });
});

describe('parseDuration', () => {
  it.each([
    ['500ms', 500],
    ['5s', 5_000],
    ['1m', 60_000],
  ])('%s → %d ms', (raw, expected) => {
    expect(parseDuration('X', raw, 0)).toBe(expected);
  });
});
