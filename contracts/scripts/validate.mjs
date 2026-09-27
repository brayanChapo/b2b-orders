// Valida los contratos y sus fixtures. Se ejecuta en CI ante cualquier cambio en /contracts.
//   npm ci && npm run validate
// Sale con código 1 si alguna verificación falla.

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import yaml from 'js-yaml';
import SwaggerParser from '@apidevtools/swagger-parser';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
const jsonFiles = (dir) =>
  existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => join(dir, f)) : [];

const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
addFormats(ajv);

let failures = 0;
const ok = (msg) => console.log(`  ✔ ${msg}`);
const fail = (msg, errors) => {
  failures++;
  console.log(`  ✘ ${msg}`);
  if (errors) console.log(`    ${ajv.errorsText(errors, { separator: '\n    ' })}`);
};

function compile(relPath) {
  const schema = readJson(join(root, relPath));
  return ajv.compile(schema);
}

function expectAll(validate, files, shouldPass, label) {
  if (files.length === 0) fail(`${label}: no hay fixtures`);
  for (const file of files) {
    const valid = validate(readJson(file));
    const name = `${label}/${basename(file)}`;
    if (valid === shouldPass) ok(`${name} → ${shouldPass ? 'válido' : 'rechazado'} (esperado)`);
    else fail(`${name} → se esperaba ${shouldPass ? 'válido' : 'rechazado'}`, valid ? null : validate.errors);
  }
}

// 1. Eventos
console.log('\nEventos');
const created = compile('events/orders.created.v1.schema.json');
const createdEx = join(root, 'events/examples/orders.created.v1');
expectAll(created, jsonFiles(join(createdEx, 'valid')), true, 'orders.created.v1/valid');
expectAll(created, jsonFiles(join(createdEx, 'invalid-schema')), false, 'orders.created.v1/invalid-schema');
// Las reglas semánticas NO son parte del esquema: estos fixtures deben pasar el esquema
// y ser rechazados por el consumidor (tests de order-processor).
expectAll(created, jsonFiles(join(createdEx, 'invalid-semantic')), true, 'orders.created.v1/invalid-semantic');

const processed = compile('events/orders.processed.v1.schema.json');
expectAll(processed, jsonFiles(join(root, 'events/examples/orders.processed.v1')), true, 'orders.processed.v1');

// Invariantes condicionales de orders.processed.v1
const approved = readJson(join(root, 'events/examples/orders.processed.v1/approved-mx-golden.json'));
const brokenApproved = { ...approved, totals: null };
if (!processed(brokenApproved)) ok('orders.processed.v1: APPROVED sin totals → rechazado (esperado)');
else fail('orders.processed.v1: APPROVED sin totals debería ser inválido');
const brokenRejected = { ...approved, status: 'REJECTED', reason: null };
if (!processed(brokenRejected)) ok('orders.processed.v1: REJECTED sin reason → rechazado (esperado)');
else fail('orders.processed.v1: REJECTED sin reason debería ser inválido');

const dlt = compile('events/orders.processing.dlt.headers.schema.json');
expectAll(dlt, jsonFiles(join(root, 'events/examples/orders.processing.dlt')), true, 'orders.processing.dlt');

// 2. HTTP
console.log('\nHTTP');
const error = compile('http/error.schema.json');
const httpEx = join(root, 'http/examples');
expectAll(error, jsonFiles(httpEx).filter((f) => basename(f).startsWith('error-')), true, 'http/error');

for (const [spec, schemaName, prefix] of [
  ['http/products-api.openapi.yaml', 'Product', 'product-'],
  ['http/clients-api.openapi.yaml', 'Client', 'client-'],
]) {
  const specPath = join(root, spec);
  try {
    await SwaggerParser.validate(specPath);
    ok(`${spec} es un OpenAPI válido`);
  } catch (e) {
    fail(`${spec} no es un OpenAPI válido: ${e.message}`);
    continue;
  }
  const doc = yaml.load(readFileSync(specPath, 'utf8'));
  const validate = ajv.compile(doc.components.schemas[schemaName]);
  expectAll(validate, jsonFiles(httpEx).filter((f) => basename(f).startsWith(prefix)), true, `${spec}#${schemaName}`);
}

console.log(failures === 0 ? '\nContratos OK\n' : `\n${failures} verificación(es) fallida(s)\n`);
process.exit(failures === 0 ? 0 : 1);
