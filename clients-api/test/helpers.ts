import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { AppModule } from '../src/app.module';
import { configureApp } from '../src/app.setup';
import { ClientRepository } from '../src/clients/application/client.repository';
import { AppConfig } from '../src/config/app-config';

export const baseConfig: AppConfig = {
  port: 0,
  requestTimeoutMs: 1_000,
  shutdownTimeoutMs: 1_000,
  faultInjectionEnabled: false,
  logLevels: [],
};

export async function createApp(
  config: Partial<AppConfig> = {},
  repository?: ClientRepository,
): Promise<INestApplication> {
  const builder = Test.createTestingModule({ imports: [AppModule.register({ ...baseConfig, ...config })] });
  if (repository !== undefined) {
    builder.overrideProvider(ClientRepository).useValue(repository);
  }
  const moduleRef = await builder.compile();
  const app = moduleRef.createNestApplication({ logger: false });
  configureApp(app);
  await app.init();
  return app;
}
