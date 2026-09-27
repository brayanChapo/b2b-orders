import { Controller, Get, HttpStatus, Res } from '@nestjs/common';
import { Response } from 'express';
import { ShutdownState } from './shutdown-state';

interface HealthBody {
  readonly status: 'UP' | 'DOWN';
}

@Controller('health')
export class HealthController {
  constructor(private readonly state: ShutdownState) {}

  @Get()
  check(@Res({ passthrough: true }) res: Response): HealthBody {
    if (this.state.isShuttingDown) {
      res.status(HttpStatus.SERVICE_UNAVAILABLE);
      return { status: 'DOWN' };
    }
    return { status: 'UP' };
  }
}
