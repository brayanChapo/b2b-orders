import { randomBytes } from 'node:crypto';

const TRACEPARENT = /^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$/;

const REQUEST_ID = /^[A-Za-z0-9._-]{1,64}$/;

export function resolveTraceId(traceparent: string | undefined, requestId: string | undefined): string {
  const fromTraceparent = traceparent === undefined ? undefined : TRACEPARENT.exec(traceparent)?.[1];
  if (fromTraceparent !== undefined) return fromTraceparent;
  if (requestId !== undefined && REQUEST_ID.test(requestId)) return requestId;
  return randomBytes(16).toString('hex');
}
