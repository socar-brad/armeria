/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpResponseDecoder.HttpResponseWrapper;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.ClosedPublisherException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.HttpObjectEncoder;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

final class HttpRequestSubscriber implements Subscriber<HttpObject>, ChannelFutureListener {

    private static final Logger logger = LoggerFactory.getLogger(HttpRequestSubscriber.class);

    enum State {
        NEEDS_TO_WRITE_FIRST_HEADER,
        NEEDS_DATA_OR_TRAILERS,
        DONE
    }

    private final Channel ch;
    private final InetSocketAddress remoteAddress;
    private final HttpObjectEncoder encoder;
    private final int id;
    private final HttpRequest request;
    private final HttpResponseWrapper response;
    private final ClientRequestContext reqCtx;
    private final RequestLogBuilder logBuilder;
    private final long timeoutMillis;
    @Nullable
    private Subscription subscription;
    @Nullable
    private ScheduledFuture<?> timeoutFuture;
    private State state = State.NEEDS_TO_WRITE_FIRST_HEADER;
    private boolean isSubscriptionCompleted;

    private boolean loggedRequestFirstBytesTransferred;

    HttpRequestSubscriber(Channel ch, SocketAddress remoteAddress, HttpObjectEncoder encoder,
                          int id, HttpRequest request, HttpResponseWrapper response,
                          ClientRequestContext reqCtx, long timeoutMillis) {

        this.ch = ch;
        this.remoteAddress = (InetSocketAddress) remoteAddress;
        this.encoder = encoder;
        this.id = id;
        this.request = request;
        this.response = response;
        this.reqCtx = reqCtx;
        logBuilder = reqCtx.logBuilder();
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Invoked on each write of an {@link HttpObject}.
     */
    @Override
    public void operationComplete(ChannelFuture future) throws Exception {
        // If a message has been sent out, cancel the timeout for starting a request.
        cancelTimeout();

        if (future.isSuccess()) {
            // The first write is always the first headers, so log that we finished our first transfer over the
            // wire.
            if (!loggedRequestFirstBytesTransferred) {
                logBuilder.requestFirstBytesTransferred();
                loggedRequestFirstBytesTransferred = true;
            }

            if (state == State.DONE) {
                // Successfully sent the request; schedule the response timeout.
                response.scheduleTimeout(ch.eventLoop());
            }

            // Request more messages regardless whether the state is DONE. It makes the producer have
            // a chance to produce the last call such as 'onComplete' and 'onError' when there are
            // no more messages it can produce.
            if (!isSubscriptionCompleted) {
                assert subscription != null;
                subscription.request(1);
            }
            return;
        }

        fail(future.cause());

        final Throwable cause = future.cause();
        if (!(cause instanceof ClosedPublisherException)) {
            final Channel ch = future.channel();
            Exceptions.logIfUnexpected(logger, ch, HttpSession.get(ch).protocol(), cause);
            ch.close();
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        assert this.subscription == null;
        this.subscription = subscription;

        final EventLoop eventLoop = ch.eventLoop();
        if (timeoutMillis > 0) {
            // The timer would be executed if the first message has not been sent out within the timeout.
            timeoutFuture = eventLoop.schedule(
                    () -> failAndRespond(WriteTimeoutException.get()),
                    timeoutMillis, TimeUnit.MILLISECONDS);
        }

        // NB: This must be invoked at the end of this method because otherwise the callback methods in this
        //     class can be called before the member fields (subscription and timeoutFuture) are initialized.
        //     It is because the successful write of the first headers will trigger subscription.request(1).
        writeFirstHeader();
    }

    private void writeFirstHeader() {
        final HttpSession session = HttpSession.get(ch);
        if (!session.canSendRequest()) {
            failAndRespond(new UnprocessedRequestException(ClosedSessionException.get()));
            return;
        }

        final RequestHeaders firstHeaders = autoFillHeaders();

        final SessionProtocol protocol = session.protocol();
        assert protocol != null;
        logBuilder.startRequest(ch, protocol);
        logBuilder.requestHeaders(firstHeaders);

        if (request.isEmpty()) {
            state = State.DONE;
            write0(firstHeaders, true, true);
        } else {
            state = State.NEEDS_DATA_OR_TRAILERS;
            write0(firstHeaders, false, true);
        }
    }

    private RequestHeaders autoFillHeaders() {
        final RequestHeadersBuilder requestHeaders = request.headers().toBuilder();
        final HttpHeaders additionalHeaders = reqCtx.additionalRequestHeaders();
        if (!additionalHeaders.isEmpty()) {
            requestHeaders.setIfAbsent(additionalHeaders);
        }

        final SessionProtocol sessionProtocol = reqCtx.sessionProtocol();
        if (requestHeaders.authority() == null) {
            final String hostname = remoteAddress.getHostName();
            final int port = remoteAddress.getPort();

            final String authority;
            if (port == sessionProtocol.defaultPort()) {
                authority = hostname;
            } else {
                final StringBuilder buf = new StringBuilder(hostname.length() + 6);
                buf.append(hostname);
                buf.append(':');
                buf.append(port);
                authority = buf.toString();
            }

            requestHeaders.add(HttpHeaderNames.AUTHORITY, authority);
        }

        if (!requestHeaders.contains(HttpHeaderNames.SCHEME)) {
            requestHeaders.add(HttpHeaderNames.SCHEME, sessionProtocol.isTls() ? "https" : "http");
        }

        if (!requestHeaders.contains(HttpHeaderNames.USER_AGENT)) {
            requestHeaders.add(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT.toString());
        }
        return requestHeaders.build();
    }

    @Override
    public void onNext(HttpObject o) {
        if (!(o instanceof HttpData) && !(o instanceof HttpHeaders)) {
            throw newIllegalStateException(
                    "published an HttpObject that's neither Http2Headers nor Http2Data: " + o);
        }

        boolean endOfStream = o.isEndOfStream();
        switch (state) {
            case NEEDS_DATA_OR_TRAILERS: {
                if (o instanceof HttpHeaders) {
                    final HttpHeaders trailers = (HttpHeaders) o;
                    if (trailers.contains(HttpHeaderNames.STATUS)) {
                        throw newIllegalStateException("published a trailers with status: " + o);
                    }
                    // Trailers always end the stream even if not explicitly set.
                    endOfStream = true;
                    logBuilder.requestTrailers(trailers);
                } else {
                    logBuilder.increaseRequestLength((HttpData) o);
                }
                write(o, endOfStream, true);
                break;
            }
            case DONE:
                // Cancel the subscription if any message comes here after the state has been changed to DONE.
                cancelSubscription();
                ReferenceCountUtil.safeRelease(o);
                break;
        }
    }

    @Override
    public void onError(Throwable cause) {
        isSubscriptionCompleted = true;
        failAndRespond(cause);
    }

    @Override
    public void onComplete() {
        isSubscriptionCompleted = true;
        cancelTimeout();

        if (state != State.DONE) {
            write(HttpData.EMPTY_DATA, true, true);
        }
    }

    private void write(HttpObject o, boolean endOfStream, boolean flush) {
        if (!ch.isActive()) {
            ReferenceCountUtil.safeRelease(o);
            fail(ClosedSessionException.get());
            return;
        }

        if (endOfStream) {
            state = State.DONE;
        }

        write0(o, endOfStream, flush);
    }

    private void write0(HttpObject o, boolean endOfStream, boolean flush) {
        final ChannelFuture future;
        if (o instanceof HttpHeaders) {
            future = encoder.writeHeaders(id, streamId(), (HttpHeaders) o, endOfStream);
        } else {
            future = encoder.writeData(id, streamId(), (HttpData) o, endOfStream);
        }

        if (endOfStream) {
            logBuilder.endRequest();
        }

        future.addListener(this);
        if (flush) {
            ch.flush();
        }
    }

    private int streamId() {
        return (id << 1) + 1;
    }

    private void fail(Throwable cause) {
        state = State.DONE;
        logBuilder.endRequest(cause);
        logBuilder.endResponse(cause);
        cancelSubscription();
    }

    private void cancelSubscription() {
        isSubscriptionCompleted = true;
        assert subscription != null;
        subscription.cancel();
    }

    private void failAndRespond(Throwable cause) {
        fail(cause);

        final Http2Error error;
        if (response.isOpen()) {
            response.close(cause);
            error = Http2Error.INTERNAL_ERROR;
        } else if (cause instanceof WriteTimeoutException || cause instanceof AbortedStreamException) {
            error = Http2Error.CANCEL;
        } else {
            Exceptions.logIfUnexpected(logger, ch,
                                       HttpSession.get(ch).protocol(),
                                       "a request publisher raised an exception", cause);
            error = Http2Error.INTERNAL_ERROR;
        }

        if (ch.isActive()) {
            encoder.writeReset(id, streamId(), error);
            ch.flush();
        }
    }

    private boolean cancelTimeout() {
        final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
        if (timeoutFuture == null) {
            return true;
        }

        this.timeoutFuture = null;
        return timeoutFuture.cancel(false);
    }

    private IllegalStateException newIllegalStateException(String msg) {
        final IllegalStateException cause = new IllegalStateException(msg);
        fail(cause);
        return cause;
    }
}
