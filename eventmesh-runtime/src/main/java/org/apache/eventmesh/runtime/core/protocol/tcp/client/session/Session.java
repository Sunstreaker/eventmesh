/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.core.protocol.tcp.client.session;

import static org.apache.eventmesh.common.protocol.tcp.Command.LISTEN_RESPONSE;

import org.apache.eventmesh.api.SendCallback;
import org.apache.eventmesh.common.protocol.SubscriptionItem;
import org.apache.eventmesh.common.protocol.tcp.Header;
import org.apache.eventmesh.common.protocol.tcp.OPStatus;
import org.apache.eventmesh.common.protocol.tcp.Package;
import org.apache.eventmesh.common.protocol.tcp.UserAgent;
import org.apache.eventmesh.runtime.configuration.EventMeshTCPConfiguration;
import org.apache.eventmesh.runtime.constants.EventMeshConstants;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.group.ClientGroupWrapper;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.session.push.DownStreamMsgContext;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.session.push.SessionPusher;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.session.send.EventMeshTcpSendResult;
import org.apache.eventmesh.runtime.core.protocol.tcp.client.session.send.SessionSender;
import org.apache.eventmesh.runtime.util.RemotingHelper;
import org.apache.eventmesh.runtime.util.Utils;

import org.apache.commons.lang3.time.DateFormatUtils;

import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cloudevents.CloudEvent;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;


import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Session {

    protected static final Logger MESSAGE_LOGGER = LoggerFactory.getLogger(EventMeshConstants.MESSAGE);

    private static final Logger SUBSCRIB_LOGGER = LoggerFactory.getLogger("subscribeLogger");

    private UserAgent client;

    private InetSocketAddress remoteAddress;

    protected ChannelHandlerContext context;

    private WeakReference<ClientGroupWrapper> clientGroupWrapper;

    private EventMeshTCPConfiguration eventMeshTCPConfiguration;

    private SessionPusher pusher;

    private SessionSender sender;

    private final long createTime = System.currentTimeMillis();

    private long lastHeartbeatTime = System.currentTimeMillis();

    private long isolateTime = 0;

    private SessionContext sessionContext = new SessionContext(this);

    private boolean listenRspSend = false;

    private final ReentrantLock listenRspLock = new ReentrantLock();

    private String listenRequestSeq = null;

    protected SessionState sessionState = SessionState.CREATED;

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public void notifyHeartbeat(long heartbeatTime) throws Exception {
        this.lastHeartbeatTime = heartbeatTime;
    }

    public SessionState getSessionState() {
        return sessionState;
    }

    public void setSessionState(SessionState sessionState) {
        this.sessionState = sessionState;
    }

    public void setClient(UserAgent client) {
        this.client = client;
    }

    public SessionPusher getPusher() {
        return pusher;
    }

    public void setPusher(SessionPusher pusher) {
        this.pusher = pusher;
    }

    public SessionSender getSender() {
        return sender;
    }

    public void setSender(SessionSender sender) {
        this.sender = sender;
    }

    public void setLastHeartbeatTime(long lastHeartbeatTime) {
        this.lastHeartbeatTime = lastHeartbeatTime;
    }

    public SessionContext getSessionContext() {
        return sessionContext;
    }

    public void setSessionContext(SessionContext sessionContext) {
        this.sessionContext = sessionContext;
    }

    public ChannelHandlerContext getContext() {
        return context;
    }

    public void setContext(ChannelHandlerContext context) {
        this.context = context;
    }

    public UserAgent getClient() {
        return client;
    }

    public String getListenRequestSeq() {
        return listenRequestSeq;
    }

    public void setListenRequestSeq(String listenRequestSeq) {
        this.listenRequestSeq = listenRequestSeq;
    }

    public void subscribe(List<SubscriptionItem> items) throws Exception {
        for (SubscriptionItem item : items) {
            sessionContext.getSubscribeTopics().putIfAbsent(item.getTopic(), item);
            Objects.requireNonNull(clientGroupWrapper.get()).subscribe(item);

            Objects.requireNonNull(clientGroupWrapper.get()).getMqProducerWrapper().getMeshMQProducer()
                .checkTopicExist(item.getTopic());

            Objects.requireNonNull(clientGroupWrapper.get()).addSubscription(item, this);
            SUBSCRIB_LOGGER.info("subscribe|succeed|topic={}|user={}", item.getTopic(), client);
        }
    }

    public void unsubscribe(List<SubscriptionItem> items) throws Exception {
        for (SubscriptionItem item : items) {
            sessionContext.getSubscribeTopics().remove(item.getTopic());
            Objects.requireNonNull(clientGroupWrapper.get()).removeSubscription(item, this);

            if (!Objects.requireNonNull(clientGroupWrapper.get()).hasSubscription(item.getTopic())) {
                Objects.requireNonNull(clientGroupWrapper.get()).unsubscribe(item);
                SUBSCRIB_LOGGER.info("unSubscribe|succeed|topic={}|lastUser={}", item.getTopic(), client);
            }
        }
    }

    public EventMeshTcpSendResult upstreamMsg(Header header, CloudEvent event, SendCallback sendCallback,
        long startTime, long taskExecuteTime) {
        String topic = event.getSubject();
        sessionContext.getSendTopics().putIfAbsent(topic, topic);
        return sender.send(header, event, sendCallback, startTime, taskExecuteTime);
    }

    public void downstreamMsg(DownStreamMsgContext downStreamMsgContext) {
        long currTime = System.currentTimeMillis();
        trySendListenResponse(new Header(LISTEN_RESPONSE, OPStatus.SUCCESS.getCode(), "succeed",
            getListenRequestSeq()), currTime, currTime);

        pusher.push(downStreamMsgContext);
    }

    public boolean isIsolated() {
        return System.currentTimeMillis() < isolateTime;
    }

    public void write2Client(final Package pkg) {

        try {
            if (SessionState.CLOSED == sessionState) {
                return;
            }

            context.writeAndFlush(pkg).addListener(
                new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            MESSAGE_LOGGER.error("write2Client fail, pkg[{}] session[{}]", pkg, this);
                        } else {
                            Objects.requireNonNull(clientGroupWrapper.get())
                                .getEventMeshTcpMonitor()
                                .getTcpSummaryMetrics()
                                .getEventMesh2clientMsgNum()
                                .incrementAndGet();
                        }
                    }
                }
            );
        } catch (Exception e) {
            log.error("exception while write2Client", e);
        }
    }

    @Override
    public String toString() {
        return "Session{"
            +
            "sysId=" + Objects.requireNonNull(clientGroupWrapper.get()).getSysId()
            +
            ",remoteAddr=" + RemotingHelper.parseSocketAddressAddr(remoteAddress)
            +
            ",client=" + client
            +
            ",sessionState=" + sessionState
            +
            ",sessionContext=" + sessionContext
            +
            ",pusher=" + pusher
            +
            ",sender=" + sender
            +
            ",createTime=" + DateFormatUtils.format(createTime, EventMeshConstants.DATE_FORMAT)
            +
            ",lastHeartbeatTime=" + DateFormatUtils.format(lastHeartbeatTime, EventMeshConstants.DATE_FORMAT) + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Session session = (Session) o;
        if (!Objects.equals(client, session.client)) {
            return false;
        }
        if (!Objects.equals(context, session.context)) {
            return false;
        }
        
        return Objects.equals(sessionState, session.sessionState);

    }

    @Override
    public int hashCode() {
        int result = 1001;   //primeNumber
        if (null != client) {
            result += 31 * result + Objects.hash(client);
        }

        if (null != context) {
            result += 31 * result + Objects.hash(context);
        }

        if (null != sessionState) {
            result += 31 * result + Objects.hash(sessionState);
        }
        return result;
    }

    public WeakReference<ClientGroupWrapper> getClientGroupWrapper() {
        return clientGroupWrapper;
    }

    public void setClientGroupWrapper(WeakReference<ClientGroupWrapper> clientGroupWrapper) {
        this.clientGroupWrapper = clientGroupWrapper;
    }

    public Session(UserAgent client, ChannelHandlerContext context, EventMeshTCPConfiguration eventMeshTCPConfiguration) {
        this.client = client;
        this.context = context;
        this.eventMeshTCPConfiguration = eventMeshTCPConfiguration;
        this.remoteAddress = (InetSocketAddress) context.channel().remoteAddress();
        this.sender = new SessionSender(this);
        this.pusher = new SessionPusher(this);
    }

    public EventMeshTCPConfiguration getEventMeshTCPConfiguration() {
        return eventMeshTCPConfiguration;
    }

    public void setEventMeshTCPConfiguration(EventMeshTCPConfiguration eventMeshTCPConfiguration) {
        this.eventMeshTCPConfiguration = eventMeshTCPConfiguration;
    }

    public void trySendListenResponse(Header header, long startTime, long taskExecuteTime) {
        if (!listenRspSend && listenRspLock.tryLock()) {
            if (header == null) {
                header = new Header(LISTEN_RESPONSE, OPStatus.SUCCESS.getCode(), "succeed", null);
            }
            Package msg = new Package();
            msg.setHeader(header);

            // TODO: if startTime is modified
            Utils.writeAndFlush(msg, startTime, taskExecuteTime, context, this);
            listenRspSend = true;

            listenRspLock.unlock();
        }
    }

    public long getIsolateTime() {
        return isolateTime;
    }

    public void setIsolateTime(long isolateTime) {
        this.isolateTime = isolateTime;
    }

    public boolean isAvailable(String topic) {
        if (SessionState.CLOSED == sessionState) {
            log.warn("session is not available because session has been closed,topic:{},client:{}", topic, client);
            return false;
        }

        if (!sessionContext.getSubscribeTopics().containsKey(topic)) {
            log.warn("session is not available because session has not subscribe topic:{},client:{}", topic, client);
            return false;
        }

        return true;
    }

    public boolean isRunning() {
        if (SessionState.RUNNING != sessionState) {
            log.warn("session is not running, state:{} client:{}", sessionState, client);
            return false;
        }
        return true;
    }
}
