/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.apm.examples.vertx.opentracing.ordermanager;

import java.util.logging.Logger;

import org.hawkular.apm.client.opentracing.APMTracer;
import org.hawkular.apm.examples.vertx.opentracing.common.HttpHeadersExtractAdapter;
import org.hawkular.apm.examples.vertx.opentracing.common.VertxMessageInjectAdapter;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * @author gbrown
 * @author Juraci Paixão Kröhling
 */
class ListOrdersHandler extends BaseHandler implements Handler<RoutingContext> {
    private static final Logger logger = Logger.getLogger(ListOrdersHandler.class.getName());
    private Tracer tracer = new APMTracer();

    @Override
    public void handle(RoutingContext context) {
        context.request().bodyHandler(buf -> {
            logger.info("Handling request");
            SpanContext spanCtx = tracer.extract(Format.Builtin.TEXT_MAP, new HttpHeadersExtractAdapter(context.request().headers()));
            Span listOrdersSpan = tracer.buildSpan("GET")
                    .asChildOf(spanCtx)
                    .withTag("http.url", "/orders")
                    .withTag("transaction", "List My Orders")
                    .start();

            JsonObject acct = buf.toJsonObject();
            HttpServerResponse response = context.response();

            Span getOrdersSpan = tracer.buildSpan("GetOrdersFromLog").asChildOf(listOrdersSpan).start();
            tracer.inject(getOrdersSpan.context(), Format.Builtin.TEXT_MAP, new VertxMessageInjectAdapter(acct));

            context.vertx().eventBus().send("OrderLog.getOrders", acct, logresp -> {
                getOrdersSpan.finish();

                if (logresp.succeeded()) {
                    logger.info("Got orders");
                    JsonArray orders = (JsonArray) logresp.result().body();
                    response.putHeader("content-type", "application/json").setStatusCode(200).end(orders.encodePrettily());
                    listOrdersSpan.finish();
                } else {
                    logger.info("Failed to get orders");
                    sendError(500, logresp.cause().getMessage(), response, listOrdersSpan);
                }
            });
        });

    }
}
