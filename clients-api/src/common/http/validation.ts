import { ValidationError, ValidationPipe } from '@nestjs/common';
import { ApiError, ErrorCode, FieldIssue } from './api-error';

export function createValidationPipe(): ValidationPipe {
  return new ValidationPipe({
    whitelist: true,
    forbidUnknownValues: true,
    stopAtFirstError: true,
    exceptionFactory: (errors: ValidationError[]) =>
      new ApiError(400, ErrorCode.INVALID_PARAMETER, 'Invalid request parameters', toIssues(errors)),
  });
}

function toIssues(errors: ValidationError[]): FieldIssue[] {
  return errors.map((error) => ({
    field: error.property,
    issue: Object.values(error.constraints ?? {})[0] ?? 'is invalid',
  }));
}
