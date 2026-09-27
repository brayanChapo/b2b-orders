/** @type {import('jest').Config} */
const tsJest = ['ts-jest', { tsconfig: 'tsconfig.json' }];

module.exports = {
  projects: [
    {
      displayName: 'unit',
      testEnvironment: 'node',
      roots: ['<rootDir>/src'],
      testMatch: ['**/*.spec.ts'],
      transform: { '^.+\\.ts$': tsJest },
    },
    {
      displayName: 'e2e',
      testEnvironment: 'node',
      roots: ['<rootDir>/test'],
      testMatch: ['**/*.e2e-spec.ts'],
      transform: { '^.+\\.ts$': tsJest },
    },
  ],
  collectCoverageFrom: ['src/**/*.ts', '!src/main.ts'],
};
