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

package net.openhft.chronicle.wire.channel;

import net.openhft.chronicle.bytes.MethodReader;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.ClosedIORuntimeException;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.threads.Pauser;
import net.openhft.chronicle.threads.PauserMode;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.MarshallableIn;
import net.openhft.chronicle.wire.MarshallableOut;
import net.openhft.chronicle.wire.channel.impl.BufferedChronicleChannel;
import net.openhft.chronicle.wire.channel.impl.SocketRegistry;
import net.openhft.chronicle.wire.channel.impl.TCPChronicleChannel;
import net.openhft.chronicle.wire.converter.NanoTime;

import java.net.URL;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public interface ChronicleChannel extends Closeable, MarshallableOut, MarshallableIn {
    static ChronicleChannel newChannel(SocketRegistry socketRegistry, ChronicleChannelCfg channelCfg, ChannelHeader headerOut) {
        TCPChronicleChannel simpleConnection = new TCPChronicleChannel(channelCfg, headerOut, socketRegistry);
        final ChannelHeader marshallable = simpleConnection.headerIn();
        Jvm.debug().on(ChronicleChannel.class, "Client got " + marshallable);
        if (marshallable instanceof RedirectHeader) {
            Closeable.closeQuietly(simpleConnection);
            RedirectHeader rh = (RedirectHeader) marshallable;
            for (String location : rh.locations()) {
                try {
                    URL url = ChronicleContext.urlFor(location);
                    channelCfg.hostname(url.getHost());
                    channelCfg.port(url.getPort());
                    return newChannel(socketRegistry, channelCfg, headerOut);

                } catch (IORuntimeException e) {
                    Jvm.debug().on(ChronicleChannel.class, e);
                }
            }
            throw new IORuntimeException("No urls available " + rh);
        }
        return channelCfg.buffered()
                ? new BufferedChronicleChannel(simpleConnection, channelCfg.pauserMode().get())
                : simpleConnection;
    }

    ChronicleChannelCfg channelCfg();

    ChannelHeader headerOut();

    ChannelHeader headerIn();

    /**
     * Read one event and return a value
     *
     * @param eventType of the event read
     * @return any data transfer object
     * @throws ClosedIORuntimeException if this ChronicleChannel is closed
     */
    default <T> T readOne(StringBuilder eventType, Class<T> expectedType) throws ClosedIORuntimeException {
        while (!isClosed()) {
            try (DocumentContext dc = readingDocument()) {
                if (dc.isPresent()) {
                    return dc.wire().read(eventType).object(expectedType);
                }
            }
        }
        throw new ClosedIORuntimeException("Closed");
    }

    /**
     * Reading all events and call the same method on the event handler
     *
     * @param eventHandler to handle events
     * @return a Runnable that can be passed to a Thread or ExecutorService
     */
    default Runnable eventHandlerAsRunnable(Object eventHandler) {
        @SuppressWarnings("resource") final MethodReader reader = methodReader(eventHandler);
        final BooleanSupplier handlerClosed;
        if (eventHandler instanceof Closeable) {
            Closeable sh = (Closeable) eventHandler;
            handlerClosed = sh::isClosed;
        } else {
            handlerClosed = () -> false;
        }

        return () -> {
            try {
                PauserMode pauserMode = channelCfg().pauserMode();
                if (pauserMode == null)
                    pauserMode = PauserMode.balanced;
                Pauser pauser = pauserMode.get();
                while (true) {
                    if (isClosed()) {
                        Jvm.debug().on(eventHandler.getClass(), "Reader on " + this + " is closed");
                        break;
                    }
                    if (handlerClosed.getAsBoolean()) {
                        Jvm.debug().on(eventHandler.getClass(), "Handler " + eventHandler + " is closed");
                        break;
                    }

                    if (reader.readOne())
                        pauser.reset();
                    else
                        pauser.pause();
                }
            } catch (Throwable t) {
                if (!isClosed() && !handlerClosed.getAsBoolean())
                    Jvm.warn().on(eventHandler.getClass(), "Error stopped reading thread", t);
            } finally {
                Closeable.closeQuietly(reader);
                Closeable.closeQuietly(eventHandler);
            }
        };
    }

    /**
     * Send a test message so the caller can wait for the response via lastTestMessage()
     *
     * @param now a monotonically increasing timestamp
     */
    void testMessage(@NanoTime long now);

    /**
     * @return the highest timestamp received
     */
    long lastTestMessage();
}
