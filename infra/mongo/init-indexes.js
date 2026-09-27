const dbName = process.env.MONGO_DB || 'orders';
const target = db.getSiblingDB(dbName);
const SEVEN_DAYS_S = 7 * 24 * 60 * 60;


const collections = ['orders', 'outbox', 'order_events', 'delivery_attempts'];
const existing = target.getCollectionNames();
collections.forEach((name) => {
  if (!existing.includes(name)) {
    target.createCollection(name);
    print(`colección creada: ${dbName}.${name}`);
  } else {
    print(`colección existente: ${dbName}.${name}`);
  }
});

// orders: un documento por pedido (_id = orderId, único por definición).
// eventId único: segunda barrera contra el mismo evento aplicado en dos pedidos.
target.orders.createIndex({ eventId: 1 }, { unique: true, name: 'ux_orders_eventId' });
// Consultas operativas: "pedidos en TECHNICAL_FAILURE de la última hora".
target.orders.createIndex({ status: 1, processedAt: -1 }, { name: 'ix_orders_status_processedAt' });

// outbox: el relay busca entradas pendientes en orden de creación (ADR-002).
target.outbox.createIndex({ status: 1, createdAt: 1 }, { name: 'ix_outbox_status_createdAt' });
// TTL: solo expiran las entradas ya enviadas (las PENDING no tienen sentAt).
target.outbox.createIndex({ sentAt: 1 }, { expireAfterSeconds: SEVEN_DAYS_S, name: 'ttl_outbox_sentAt' });

// order_events: registro de cada evento recibido (_id = eventId), para trazabilidad.
target.order_events.createIndex({ orderId: 1, eventVersion: 1 }, { name: 'ix_order_events_orderId_version' });

// delivery_attempts: detección de mensajes veneno y pedidos atascados (sección 5.6).
target.delivery_attempts.createIndex({ status: 1, lastSeenAt: 1 }, { name: 'ix_delivery_status_lastSeenAt' });
target.delivery_attempts.createIndex({ orderId: 1 }, { sparse: true, name: 'ix_delivery_orderId' });
target.delivery_attempts.createIndex({ lastSeenAt: 1 }, { expireAfterSeconds: SEVEN_DAYS_S, name: 'ttl_delivery_lastSeenAt' });

print('');
print(`Índices en ${dbName}:`);
collections.forEach((name) => {
  const names = target.getCollection(name).getIndexes().map((i) => i.name).join(', ');
  print(`  ${name}: ${names}`);
});
