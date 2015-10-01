/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
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
package org.hawkular.btm.server.elasticsearch;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.ActivationConfigProperty;
import javax.ejb.MessageDriven;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.hawkular.btm.api.model.analytics.ResponseTime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * @author gbrown
 */
@MessageDriven(name = "ResponseTimes_Elasticsearch", messageListenerInterface = MessageListener.class,
        activationConfig =
        {
                @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Topic"),
                @ActivationConfigProperty(propertyName = "destination", propertyValue = "ResponseTimes")
        })
@TransactionManagement(value = TransactionManagementType.CONTAINER)
@TransactionAttribute(value = TransactionAttributeType.REQUIRED)
public class ResponseTimeMDBElasticsearch implements MessageListener {

    /**  */
    private static final String RESPONSE_TIME_TYPE = "responsetime";

    private static final Logger log = Logger.getLogger(ResponseTimeMDBElasticsearch.class.getName());

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final TypeReference<java.util.List<ResponseTime>> RESPONSE_TIME_LIST =
            new TypeReference<java.util.List<ResponseTime>>() {
    };

    private ElasticsearchClient client;

    @PostConstruct
    public void init() {
        client = new ElasticsearchClient();
        try {
            client.init();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to initialise Elasticsearch", e);
        }
    }

    /* (non-Javadoc)
     * @see javax.jms.MessageListener#onMessage(javax.jms.Message)
     */
    @Override
    public void onMessage(Message message) {
        if (log.isLoggable(Level.FINEST)) {
            log.finest("Elasticsearch: Repsonse time received=" + message);
        }

        try {
            String tenantId = message.getStringProperty("tenant");

            client.initTenant(tenantId);

            String data = ((TextMessage) message).getText();

            List<ResponseTime> rts = mapper.readValue(data, RESPONSE_TIME_LIST);

            BulkRequestBuilder bulkRequestBuilder = client.getElasticsearchClient().prepareBulk();

            for (int i = 0; i < rts.size(); i++) {
                ResponseTime rt = rts.get(i);
                bulkRequestBuilder.add(client.getElasticsearchClient().prepareIndex(client.getIndex(tenantId),
                        RESPONSE_TIME_TYPE, rt.getId()).setSource(mapper.writeValueAsString(rt)));
            }

            BulkResponse bulkItemResponses = bulkRequestBuilder.execute().actionGet();

            if (bulkItemResponses.hasFailures()) {

                // TODO: Candidate for retry???
                log.severe("Failed to store response times: " + bulkItemResponses.buildFailureMessage());

                if (log.isLoggable(Level.FINEST)) {
                    log.finest("Failed to store response times to elasticsearch: "
                            + bulkItemResponses.buildFailureMessage());
                }
            } else {
                if (log.isLoggable(Level.FINEST)) {
                    log.finest("Success storing response times to elasticsearch");
                }
            }
        } catch (Exception e) {
            // TODO: Trigger retry???
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void close() {
        client.close();
    }

}