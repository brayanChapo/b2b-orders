package com.b2b.orders.application.port;

import com.b2b.orders.application.ProcessedOrder;
import com.b2b.orders.application.StoreOutcome;
import com.b2b.orders.application.StoredOrderState;
import com.b2b.orders.domain.model.OrderId;
import java.util.Optional;

public interface OrderResultStore {

    Optional<StoredOrderState> findState(OrderId orderId);

    StoreOutcome save(ProcessedOrder result);
}
