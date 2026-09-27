import { LogLevel } from '@nestjs/common';

export interface AppConfig {
  readonly port: number;
  readonly requestTimeoutMs: number;
  readonly shutdownTimeoutMs: number;
  readonly faultInjectionEnabled: boolean;
  readonly logLevels: LogLevel[];
}

/** Token de inyección de la configuración. */
export const APP_CONFIG = Symbol('APP_CONFIG');

export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

const LEVELS_BY_THRESHOLD: Record<string, LogLevel[]> = {
  debug: ['error', 'warn', 'log', 'debug'],
  info: ['error', 'warn', 'log'],
  warn: ['error', 'warn'],
  error: ['error'],
};

export function loadConfig(env: Record<string, string | undefined>): AppConfig {
  return {
    port: parsePort(env['PORT']),
    requestTimeoutMs: parseDuration('REQUEST_TIMEOUT', env['REQUEST_TIMEOUT'], 5_000),
    shutdownTimeoutMs: parseDuration('SHUTDOWN_TIMEOUT', env['SHUTDOWN_TIMEOUT'], 10_000),
    faultInjectionEnabled: parseBoolean('FAULT_INJECTION_ENABLED', env['FAULT_INJECTION_ENABLED'], false),
    logLevels: parseLogLevel(env['LOG_LEVEL']),
  };
}

function parsePort(raw: string | undefined): number {
  if (raw === undefined || raw === '') return 8080;
  const port = Number(raw);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new ConfigError(`PORT inválido: "${raw}"`);
  }
  return port;
}

/** Acepta el formato de Go usado en products-api: "500ms", "5s", "1m". */
export function parseDuration(name: string, raw: string | undefined, fallbackMs: number): number {
  if (raw === undefined || raw === '') return fallbackMs;
  const match = /^(\d+)(ms|s|m)$/.exec(raw);
  const amount = match?.[1];
  const unit = match?.[2];
  if (amount === undefined || unit === undefined || Number(amount) <= 0) {
    throw new ConfigError(`${name} inválido: "${raw}" (ejemplos: 500ms, 5s, 1m)`);
  }
  const factor = unit === 'ms' ? 1 : unit === 's' ? 1_000 : 60_000;
  return Number(amount) * factor;
}

function parseBoolean(name: string, raw: string | undefined, fallback: boolean): boolean {
  if (raw === undefined || raw === '') return fallback;
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  throw new ConfigError(`${name} inválido: "${raw}" (true o false)`);
}

function parseLogLevel(raw: string | undefined): LogLevel[] {
  const key = (raw === undefined || raw === '' ? 'info' : raw).toLowerCase();
  const levels = LEVELS_BY_THRESHOLD[key];
  if (levels === undefined) {
    throw new ConfigError(`LOG_LEVEL inválido: "${raw}" (debug, info, warn o error)`);
  }
  return levels;
}
