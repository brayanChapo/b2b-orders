import { INestApplication } from '@nestjs/common';
import { Express } from 'express';

export function configureApp(app: INestApplication): void {
  const express = app.getHttpAdapter().getInstance() as Express;
  express.disable('x-powered-by');
}
