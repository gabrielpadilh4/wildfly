/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.messaging.activemq;

import static org.jboss.as.controller.security.CredentialReference.handleCredentialReferenceUpdate;
import static org.jboss.as.controller.security.CredentialReference.rollbackCredentialStoreUpdate;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.ATTRIBUTES;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.CALL_TIMEOUT;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.CREDENTIAL_REFERENCE;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.DISCOVERY_GROUP_NAME;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.FORWARDING_ADDRESS;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.INITIAL_CONNECT_ATTEMPTS;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.PASSWORD;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.PRODUCER_WINDOW_SIZE;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.QUEUE_NAME;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.RECONNECT_ATTEMPTS;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.RECONNECT_ATTEMPTS_ON_SAME_NODE;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.USER;
import static org.wildfly.extension.messaging.activemq.BridgeDefinition.USE_DUPLICATE_DETECTION;

import java.util.ArrayList;
import java.util.List;

import org.apache.activemq.artemis.core.config.BridgeConfiguration;
import org.apache.activemq.artemis.core.config.TransformerConfiguration;
import org.apache.activemq.artemis.core.persistence.StorageManager;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ComponentConfigurationRoutingType;
import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistry;
import org.wildfly.extension.messaging.activemq._private.MessagingLogger;

/**
 * Handler for adding a bridge.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class BridgeAdd extends AbstractAddStepHandler {

    public static final BridgeAdd INSTANCE = new BridgeAdd();

    static final String CALL_TIMEOUT_PROPERTY = "org.wildfly.messaging.core.bridge.call-timeout";
    static final String ROUTING_TYPE_PROPERTY = "org.wildfly.messaging.core.bridge.%s.routing-type";

    private BridgeAdd() {
        super(ATTRIBUTES);
    }

    @Override
    protected void populateModel(final OperationContext context, final ModelNode operation, final Resource resource) throws  OperationFailedException {
        super.populateModel(context, operation, resource);
        handleCredentialReferenceUpdate(context, resource.getModel());
    }

    @Override
    protected void performRuntime(OperationContext context, ModelNode operation, ModelNode model) throws OperationFailedException {

        ServiceRegistry registry = context.getServiceRegistry(true);
        final ServiceName serviceName = MessagingServices.getActiveMQServiceName(PathAddress.pathAddress(operation.get(ModelDescriptionConstants.OP_ADDR)));
        ServiceController<?> service = registry.getService(serviceName);
        if (service != null) {

            // The original subsystem initialization is complete; use the control object to create the divert
            if (service.getState() != ServiceController.State.UP) {
                throw MessagingLogger.ROOT_LOGGER.invalidServiceState(serviceName, ServiceController.State.UP, service.getState());
            }

            final String name = PathAddress.pathAddress(operation.require(ModelDescriptionConstants.OP_ADDR)).getLastElement().getValue();

            BridgeConfiguration bridgeConfig = createBridgeConfiguration(context, name, model);

            //noinspection RedundantClassCall
            ActiveMQServer server = ActiveMQServer.class.cast(service.getValue());
            createBridge(bridgeConfig, server);

        }
        // else the initial subsystem install is not complete; MessagingSubsystemAdd will add a
        // handler that calls addBridgeConfigs
    }

    @Override
    protected void rollbackRuntime(OperationContext context, final ModelNode operation, final Resource resource) {
        rollbackCredentialStoreUpdate(CREDENTIAL_REFERENCE, context, resource);
    }

    static BridgeConfiguration createBridgeConfiguration(final ExpressionResolver expressionResolver, final String name, final ModelNode model) throws OperationFailedException {

        final String queueName = QUEUE_NAME.resolveModelAttribute(expressionResolver, model).asString();
        final String forwardingAddress = FORWARDING_ADDRESS.resolveModelAttribute(expressionResolver, model).asStringOrNull();
        final String filterString = CommonAttributes.FILTER.resolveModelAttribute(expressionResolver, model).asStringOrNull();
        final int minLargeMessageSize = CommonAttributes.MIN_LARGE_MESSAGE_SIZE.resolveModelAttribute(expressionResolver, model).asInt();
        final long retryInterval = CommonAttributes.RETRY_INTERVAL.resolveModelAttribute(expressionResolver, model).asLong();
        final double retryIntervalMultiplier = CommonAttributes.RETRY_INTERVAL_MULTIPLIER.resolveModelAttribute(expressionResolver, model).asDouble();
        final long maxRetryInterval = CommonAttributes.MAX_RETRY_INTERVAL.resolveModelAttribute(expressionResolver, model).asLong();
        final int initialConnectAttempts = INITIAL_CONNECT_ATTEMPTS.resolveModelAttribute(expressionResolver, model).asInt();
        final int reconnectAttempts = RECONNECT_ATTEMPTS.resolveModelAttribute(expressionResolver, model).asInt();
        final int reconnectAttemptsOnSameNode = RECONNECT_ATTEMPTS_ON_SAME_NODE.resolveModelAttribute(expressionResolver, model).asInt();
        final boolean useDuplicateDetection = USE_DUPLICATE_DETECTION.resolveModelAttribute(expressionResolver, model).asBoolean();
        final int confirmationWindowSize = CommonAttributes.BRIDGE_CONFIRMATION_WINDOW_SIZE.resolveModelAttribute(expressionResolver, model).asInt();
        final int producerWindowSize = PRODUCER_WINDOW_SIZE.resolveModelAttribute(expressionResolver, model).asInt();
        final long clientFailureCheckPeriod = CommonAttributes.CHECK_PERIOD.resolveModelAttribute(expressionResolver, model).asLong();
        final long connectionTTL = CommonAttributes.CONNECTION_TTL.resolveModelAttribute(expressionResolver, model).asLong();
        final ModelNode discoveryNode = DISCOVERY_GROUP_NAME.resolveModelAttribute(expressionResolver, model);
        final String discoveryGroupName = discoveryNode.isDefined() ? discoveryNode.asString() : null;
        List<String> staticConnectors = discoveryGroupName == null ? getStaticConnectors(model) : null;
        final boolean ha = CommonAttributes.HA.resolveModelAttribute(expressionResolver, model).asBoolean();
        final String user = USER.resolveModelAttribute(expressionResolver, model).asString();
        final String password = PASSWORD.resolveModelAttribute(expressionResolver, model).asString();
        String routingType = getRoutingTypeFromSystemProperty(name);
        if(routingType == null) {
            routingType = BridgeDefinition.ROUTING_TYPE.resolveModelAttribute(expressionResolver, model).asString();
        }

        Long callTimeout = getCallTimeoutFromSystemProperty();
        if (callTimeout == null) {
            callTimeout = CALL_TIMEOUT.resolveModelAttribute(expressionResolver, model).asLong();
        }

        BridgeConfiguration config = new BridgeConfiguration()
                .setName(name)
                .setQueueName(queueName)
                .setForwardingAddress(forwardingAddress)
                .setFilterString(filterString)
                .setMinLargeMessageSize(minLargeMessageSize)
                .setClientFailureCheckPeriod(clientFailureCheckPeriod)
                .setConnectionTTL(connectionTTL)
                .setRetryInterval(retryInterval)
                .setMaxRetryInterval(maxRetryInterval)
                .setRetryIntervalMultiplier(retryIntervalMultiplier)
                .setInitialConnectAttempts(initialConnectAttempts)
                .setReconnectAttempts(reconnectAttempts)
                .setReconnectAttemptsOnSameNode(reconnectAttemptsOnSameNode)
                .setUseDuplicateDetection(useDuplicateDetection)
                .setConfirmationWindowSize(confirmationWindowSize)
                .setProducerWindowSize(producerWindowSize)
                .setHA(ha)
                .setUser(user)
                .setPassword(password)
                .setCallTimeout(callTimeout)
                .setRoutingType(ComponentConfigurationRoutingType.valueOf(routingType));

        if (discoveryGroupName != null) {
            config.setDiscoveryGroupName(discoveryGroupName);
        } else {
            config.setStaticConnectors(staticConnectors);
        }
        final ModelNode transformerClassName = CommonAttributes.TRANSFORMER_CLASS_NAME.resolveModelAttribute(expressionResolver, model);
        if (transformerClassName.isDefined()) {
            config.setTransformerConfiguration(new TransformerConfiguration(transformerClassName.asString()));
        }

        return config;
    }

    private static List<String> getStaticConnectors(ModelNode model) {
        List<String> result = new ArrayList<>();
        for (ModelNode connector : model.require(CommonAttributes.STATIC_CONNECTORS).asList()) {
            result.add(connector.asString());
        }
        return result;
    }

    static void createBridge(BridgeConfiguration bridgeConfig, ActiveMQServer server) throws OperationFailedException {
        checkStarted(server);
        clearIO(server);

        try {
            if(!server.deployBridge(bridgeConfig)) {
                throw MessagingLogger.ROOT_LOGGER.failedBridgeDeployment(bridgeConfig.getName());
            }
        } catch (OperationFailedException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // TODO should this be an OFE instead?
            throw new RuntimeException(e);
        } finally {
            blockOnIO(server);
        }
    }

    static void checkStarted(ActiveMQServer server) {
        // extracted from ActiveMQServerControlImpl#checkStarted()

        if (!server.isStarted()) {
            throw MessagingLogger.ROOT_LOGGER.brokerNotStarted();
        }
    }

    static void clearIO(ActiveMQServer server) {
        // extracted from ActiveMQServerControlImpl#clearIO()

        StorageManager storageManager = server.getStorageManager();

        // the storage manager could be null on the backup on certain components
        if (storageManager != null) {
            storageManager.clearContext();
        }
    }

    static void blockOnIO(ActiveMQServer server) {
        // extracted from ActiveMQServerControlImpl#blockOnIO()

        StorageManager storageManager = server.getStorageManager();

        // the storage manager could be null on the backup on certain components
        if (storageManager != null && storageManager.isStarted()) {
            try {
                storageManager.waitOnOperations();
                storageManager.clearContext();
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    /**
     * In upstream this property is gonna be part of the management model. Here we need to get it from a system property.
     */
    private static Long getCallTimeoutFromSystemProperty() {
        String value = org.wildfly.security.manager.WildFlySecurityManager.getSystemPropertiesPrivileged().getProperty(CALL_TIMEOUT_PROPERTY);
        if (value == null) {
            return null;
        }
        return Long.parseLong(value);
    }
    /**
     * In upstream this property is gonna be part of the management model. Here we need to get it from a system property.
     */
    private static String getRoutingTypeFromSystemProperty(String name) {
        return org.wildfly.security.manager.WildFlySecurityManager.getSystemPropertiesPrivileged().getProperty(String.format(ROUTING_TYPE_PROPERTY, name));
    }
}
