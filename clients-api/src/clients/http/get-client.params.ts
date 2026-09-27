import { Matches } from 'class-validator';
import { CLIENT_ID_PATTERN } from '../domain/client';

export class GetClientParams {
  @Matches(CLIENT_ID_PATTERN, { message: "must be 1-64 characters: letters, digits or '-'" })
  clientId!: string;
}
