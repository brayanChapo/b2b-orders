package com.b2b.orders.infrastructure.config;

import com.b2b.orders.infrastructure.messaging.OrderProcessedEventMapper;
import com.b2b.orders.infrastructure.messaging.UlidGenerator;
import com.b2b.orders.infrastructure.mongo.DeliveryGuard;
import com.b2b.orders.infrastructure.mongo.EventLedger;
import com.b2b.orders.infrastructure.mongo.MongoOrderResultStore;
import com.b2b.orders.infrastructure.mongo.MongoOutboxStore;
import com.b2b.orders.infrastructure.mongo.MongoSchema;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MongoConfiguration {

    @Bean
    MongoDatabase ordersDatabase(MongoClient client, AppProperties props) {
        MongoDatabase db = client.getDatabase(props.mongodb().database());
        MongoSchema.ensure(db);
        return db;
    }

    @Bean
    EventLedger eventLedger(MongoDatabase db) {
        return new EventLedger(db);
    }

    @Bean
    MongoOrderResultStore orderResultStore(MongoClient client, MongoDatabase db, EventLedger ledger,
            OrderProcessedEventMapper mapper, UlidGenerator ids, Clock clock, AppProperties props) {
        return new MongoOrderResultStore(client, db, ledger, mapper, ids, clock,
                props.topics().output(), props.topics().deadLetter(), props.component());
    }

    @Bean
    MongoOutboxStore outboxStore(MongoDatabase db, Clock clock) {
        return new MongoOutboxStore(db, clock);
    }

    @Bean
    DeliveryGuard deliveryGuard(MongoDatabase db, AppProperties props) {
        return new DeliveryGuard(db, props.consumer().maxDeliveryCrashes());
    }
}
