import 'reflect-metadata';
import { ConsoleLogger } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';
import { configureApp } from './app.setup';
import { AppConfig, loadConfig } from './config/app-config';

async function bootstrap(): Promise<void> {
  let config: AppConfig;
  try {
    config = loadConfig(process.env);
  } catch (error) {
    console.error(`clients-api: ${error instanceof Error ? error.message : String(error)}`);
    process.exit(1);
  }

  const logger = new ConsoleLogger('clients-api', { json: true, logLevels: config.logLevels });
  const app = await NestFactory.create(AppModule.register(config), { logger });
  configureApp(app);

  await app.listen(config.port);
  logger.log({ msg: 'servidor iniciado', port: config.port, faultInjection: config.faultInjectionEnabled }, 'Bootstrap');

  // Apagado controlado. No se usa app.enableShutdownHooks() para poder acotar la espera:
  // app.close() marca /health como DOWN, deja de aceptar conexiones y espera las en curso.
  let closing = false;
  const shutdown = async (signal: NodeJS.Signals): Promise<void> => {
    if (closing) return;
    closing = true;
    logger.log({ msg: 'apagado iniciado', signal, timeoutMs: config.shutdownTimeoutMs }, 'Bootstrap');
    const force = setTimeout(() => {
      logger.error({ msg: 'apagado incompleto: se superó SHUTDOWN_TIMEOUT' }, undefined, 'Bootstrap');
      process.exit(1);
    }, config.shutdownTimeoutMs);
    force.unref();
    await app.close();
    logger.log({ msg: 'apagado completo' }, 'Bootstrap');
    process.exit(0);
  };
  process.once('SIGTERM', (signal) => void shutdown(signal));
  process.once('SIGINT', (signal) => void shutdown(signal));
}

void bootstrap();
