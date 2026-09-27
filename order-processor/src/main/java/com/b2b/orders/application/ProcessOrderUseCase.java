package com.b2b.orders.application;

import com.b2b.orders.domain.model.OrderRequest;

public interface ProcessOrderUseCase {

    ProcessingOutcome process(OrderRequest order, EventMetadata metadata);
}
