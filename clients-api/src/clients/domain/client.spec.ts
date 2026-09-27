import { isValidClientId } from './client';

describe('isValidClientId', () => {
  it.each(['CLI-99821', 'ABC123', 'A'.repeat(64)])('acepta %s', (id) => {
    expect(isValidClientId(id)).toBe(true);
  });

  it.each(['', 'A'.repeat(65), 'CLI_1', 'CLI 1', '../etc', 'CLI-1;DROP'])('rechaza "%s"', (id) => {
    expect(isValidClientId(id)).toBe(false);
  });
});
