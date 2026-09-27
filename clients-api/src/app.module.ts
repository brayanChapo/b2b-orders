import { DynamicModule, MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { APP_FILTER, APP_PIPE } from '@nestjs/core';
import { ClientsModule } from './clients/clients.module';
import { AllExceptionsFilter } from './common/http/all-exceptions.filter';
import { RequestContextMiddleware } from './common/http/request-context.middleware';
import { createValidationPipe } from './common/http/validation';
import { AppConfig } from './config/app-config';
import { AppConfigModule } from './config/app-config.module';
import { HealthModule } from './health/health.module';

@Module({})
export class AppModule implements NestModule {
  static register(config: AppConfig): DynamicModule {
    return {
      module: AppModule,
      imports: [AppConfigModule.register(config), ClientsModule, HealthModule],
      providers: [
        { provide: APP_FILTER, useClass: AllExceptionsFilter },
        { provide: APP_PIPE, useFactory: createValidationPipe },
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(RequestContextMiddleware).forRoutes('*');
  }
}
