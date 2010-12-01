/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.test.embedded.demos.messaging;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.Assert;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.TransportConfiguration;
import org.hornetq.api.core.client.ClientConsumer;
import org.hornetq.api.core.client.ClientMessage;
import org.hornetq.api.core.client.ClientProducer;
import org.hornetq.api.core.client.ClientSession;
import org.hornetq.api.core.client.ClientSessionFactory;
import org.hornetq.api.core.client.HornetQClient;
import org.hornetq.core.remoting.impl.invm.InVMConnectorFactory;
import org.jboss.arquillian.api.Deployment;
import org.jboss.arquillian.api.Run;
import org.jboss.arquillian.api.RunModeType;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.test.modular.utils.ShrinkWrapUtils;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @version $Revision: 1.1 $
 */
@RunWith(Arquillian.class)
@Run(RunModeType.IN_CONTAINER)
public class MessagingTestCase {
    private static final String QUEUE_EXAMPLE_QUEUE = "queue.exampleQueue";

    static final Logger log = Logger.getLogger(MessagingTestCase.class);

    private static final String BODY = "msg.body";

    private ClientSessionFactory sf;
    private ClientSession session;
    private ClientConsumer consumer;

    private final AtomicBoolean shutdown = new AtomicBoolean();

    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile String receivedMessage;

    @Deployment
    public static JavaArchive createDeployment() throws Exception {
        JavaArchive archive = ShrinkWrapUtils.createJavaArchive("demos/messaging-example.jar", MessagingTestCase.class.getPackage());
        return archive;
    }

    @Before
    public void start() throws Exception {
        //FIXME Arquillian Alpha bug - it also wants to execute this on the client despite this test being IN_CONTAINER
        if (this.getClass().getClassLoader() == ClassLoader.getSystemClassLoader()) {
            return;
        }

        //HornetQService set up the config and starts the HornetQServer

        //Not using JNDI so we use the core services directly
        sf = HornetQClient.createClientSessionFactory(new TransportConfiguration(InVMConnectorFactory.class.getName()));

        //Create a queue
        ClientSession coreSession = sf.createSession(false, true, true);
        coreSession.createQueue(QUEUE_EXAMPLE_QUEUE, QUEUE_EXAMPLE_QUEUE, true);
        coreSession.close();

        session = sf.createSession();

        consumer = session.createConsumer(QUEUE_EXAMPLE_QUEUE);
        session.start();

        new Thread(new Runnable() {

            @Override
            public void run() {
                while (!shutdown.get()) {
                    try {
                        ClientMessage message = consumer.receive(500);
                        if (message == null) {
                            continue;
                        }
                        String s = message.getStringProperty(BODY);
                        log.info("-----> Received: " + s);
                        receivedMessage = s;
                        latch.countDown();
                    } catch (HornetQException e) {
                        log.error("Exception, closing receiver", e);
                    }
                }
            }
        }).start();

        log.info("-----> Started queue and session");
    }

    @After
    public void stop() throws Exception {
        //FIXME Arquillian Alpha bug - it also wants to execute this on the client despite this test being IN_CONTAINER
        if (this.getClass().getClassLoader() == ClassLoader.getSystemClassLoader()) {
            return;
        }

        shutdown.set(true);
        if (session != null)
            session.close();
        ClientSession coreSession = sf.createSession(false, false, false);
        coreSession.deleteQueue(QUEUE_EXAMPLE_QUEUE);
        coreSession.close();
    }

    @Test
    public void testMessaging() throws Exception {
        sendMessage("Test");
        Assert.assertTrue(latch.await(3, TimeUnit.SECONDS));
        Assert.assertEquals("'Test' sent today", receivedMessage);
    }

    private void sendMessage(String txt) throws Exception {
        ClientProducer producer = session.createProducer(QUEUE_EXAMPLE_QUEUE);
        ClientMessage message = session.createMessage(false);

        message.putStringProperty(BODY, "'" + txt + "' sent today");
        log.info("-----> Sending message");
        producer.send(message);
    }

}
