/*
 * Copyright 2016-2022 chronicle.software
 *
 *       https://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.wire.channel.echo;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.time.SystemTimeProvider;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.WireTestCommon;
import net.openhft.chronicle.wire.channel.*;
import net.openhft.chronicle.wire.channel.impl.TCPChronicleChannel;
import org.jetbrains.annotations.Nullable;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class EchoHandlerTest extends WireTestCommon {

    private static void doTest(ChronicleContext context, ChannelHandler handler) {
        ChronicleChannel channel = context.newChannelSupplier(handler).connectionTimeoutSecs(1).get();
        Says says = channel.methodWriter(Says.class);
        says.say("Hello World");

        StringBuilder eventType = new StringBuilder();
        String text = channel.readOne(eventType, String.class);
        assertEquals("say: Hello World",
                eventType + ": " + text);
        try (DocumentContext dc = channel.readingDocument()) {
            assertFalse(dc.isPresent());
            assertFalse(dc.isMetaData());
        }

        final long now = SystemTimeProvider.CLOCK.currentTimeNanos();
        channel.testMessage(now);
        try (DocumentContext dc = channel.readingDocument()) {
            assertTrue(dc.isPresent());
            assertTrue(dc.isMetaData());
        }
        assertEquals(now, channel.lastTestMessage());
    }

    @Test
    public void internal() {
        String url = "internal://";
        try (ChronicleContext context = ChronicleContext.newContext(url)) {
            doTest(context, new EchoHandler().buffered(false));
        }
    }

    @Test
    public void server() {
        String url = "tcp://:0";
        IOTools.deleteDirWithFiles("target/server");
        try (ChronicleContext context = ChronicleContext.newContext(url)
                .name("target/server")
                .buffered(true)
                .useAffinity(true)) {
            doTest(context, new EchoHandler().buffered(false));
        }
    }

    @Test
    @Ignore(/* TODO FIX */)
    public void serverBuffered() {
        ignoreException("Closed");
        if (Jvm.isArm()) {
            ignoreException("Using Pauser.balanced() as not enough processors");
            ignoreException("bgWriter died");
        }
        String url = "tcp://:0";
        IOTools.deleteDirWithFiles("target/server");
        try (ChronicleContext context = ChronicleContext.newContext(url)
                .name("target/server")
                .buffered(true)
                .useAffinity(true)) {
            doTest(context, new EchoHandler().buffered(true));
        }
    }

    @Test
    public void gateway() throws IOException {
        ignoreException("ClosedIORuntimeException");
        String url0 = "tcp://localhost:65340";
        try (ChronicleGatewayMain gateway0 = new ChronicleGatewayMain(url0) {
            @Override
            protected ChannelHeader replaceInHeader(ChannelHeader channelHeader) {
                // for this test, the default behaviour is to act as an EchoHandler
                if (channelHeader instanceof GatewayHandler) {
                    GatewayHandler gh = (GatewayHandler) channelHeader;
                    return new EchoHandler().systemContext(gh.systemContext()).sessionName(gh.sessionName());
                }
                return new ErrorReplyHandler().errorMsg("Custom ChannelHandlers not supported");
            }
        }) {
            gateway0.name("target/zero");
            // gateway that will handle the request
            gateway0.start();

            try (ChronicleContext context = ChronicleContext.newContext(url0).name("target/client")) {
                doTest(context, new GatewayHandler());
            }
        }
    }

    @Test
    public void redirectedServer() throws IOException {
        ignoreException("ClosedIORuntimeException");
        String urlZzz = "tcp://localhost:65329";
        String url0 = "tcp://localhost:65330";
        String url1 = "tcp://localhost:65331";
        try (ChronicleGatewayMain gateway0 = new ChronicleGatewayMain(url0)) {
            gateway0.name("target/zero");
            // gateway that will handle the request
            gateway0.start();
            try (ChronicleGatewayMain gateway1 = new ChronicleGatewayMain(url1) {
                @Override
                protected ChannelHeader replaceOutHeader(ChannelHeader channelHeader) {
                    return new RedirectHeader(Arrays.asList(urlZzz, url0));
                }
            }) {
                gateway1.name("target/one");
                // gateway that will handle the redirect request
                gateway1.start();

                try (ChronicleContext context = ChronicleContext.newContext(url1).name("target/client")) {
                    doTest(context, new EchoHandler().buffered(false));
                }
            }
        }
    }

    @Test
    public void readme() {
        // start a server on an unused port
        String url = "tcp://:0";
        // create a context for new channels, all channels are closed when the context is closed
        try (ChronicleContext context = ChronicleContext.newContext(url)) {
            // open a new channel that acts as an EchoHandler
            final ChronicleChannelSupplier supplier = context.newChannelSupplier(new EchoHandler());
            System.out.println("supplier: " + supplier);
            ChronicleChannel channel = supplier.get();
            // create a proxy that turns each call to Says into an event on the channel
            Says say = channel.methodWriter(Says.class);
            // add an event
            say.say("Hello World");
            // ad a second event
            say.say("Bye now");

            // A buffer so the event name can be returned as well
            StringBuilder event = new StringBuilder();
            // read one message excepting the object after the event name to be a String
            String text = channel.readOne(event, String.class);
            // check it matches
            assertEquals("say: Hello World", event + ": " + text);

            // read the second message
            String text2 = channel.readOne(event, String.class);
            // check it matches
            assertEquals("say: Bye now", event + ": " + text2);
/*
            final long now = System.currentTimeMillis();
            channel.testMessage(now);

            Says reply = Mocker.logging(Says.class, "reply - ", System.out);
            final MethodReader methodReader = channel.methodReader(reply);
            int count = 0;
            while (channel.lastTestMessage() < now) {
                if (methodReader.readOne())
                    count++;
            }
            assertEquals(2, count);*/
        }
    }
}