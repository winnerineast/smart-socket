/*******************************************************************************
 * Copyright (c) 2017-2019, org.smartboot. All rights reserved.
 * project name: smart-socket
 * file name: UdpChannel.java
 * Date: 2019-12-31
 * Author: sandao (zhengjunweimail@163.com)
 *
 ******************************************************************************/

package org.smartboot.socket.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.smartboot.socket.buffer.BufferPage;
import org.smartboot.socket.buffer.VirtualBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;

/**
 * @author 三刀
 * @version V1.0 , 2019/8/18
 */
public final class UdpChannel<Request> {
    private static final Logger LOGGER = LoggerFactory.getLogger(UdpChannel.class);
    private BufferPage bufferPage;
    private IoServerConfig config;
    /**
     * 真实的UDP通道
     */
    private DatagramChannel channel;

    private SelectionKey selectionKey;

    /**
     * 与当前UDP通道对接的会话
     */
    private ConcurrentHashMap<String, UdpAioSession<Request>> udpAioSessionConcurrentHashMap = new ConcurrentHashMap<>();


    /**
     * 待输出消息
     */
    private LinkedBlockingQueue<ResponseTask> writeRingBuffer;

    private ResponseTask failWriteEvent;


    UdpChannel(final DatagramChannel channel, SelectionKey selectionKey, IoServerConfig config, BufferPage bufferPage) {
        this.channel = channel;
        writeRingBuffer = new LinkedBlockingQueue<>();
        this.selectionKey = selectionKey;
        this.config = config;
        this.bufferPage = bufferPage;
    }

    /**
     * @param virtualBuffer
     * @param remote
     * @throws IOException
     * @throws InterruptedException
     */
    private void write(VirtualBuffer virtualBuffer, SocketAddress remote) throws IOException {
        if (!writeRingBuffer.isEmpty() || failWriteEvent != null) {
            writeRingBuffer.offer(new ResponseTask(remote, virtualBuffer));
            return;
        }
        int size = channel.send(virtualBuffer.buffer(), remote);
        if (size > 0) {
            virtualBuffer.clean();
        } else {
            writeRingBuffer.offer(new ResponseTask(remote, virtualBuffer));
            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    void flush() throws IOException {
        while (true) {
            ResponseTask responseTask;
            if (failWriteEvent == null) {
                responseTask = writeRingBuffer.poll();
            } else {
                responseTask = failWriteEvent;
                failWriteEvent = null;
            }
            if (responseTask == null) {
                selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                return;
            }
            int size = channel.send(responseTask.response.buffer(), responseTask.remote);
            if (size > 0) {
                responseTask.response.clean();
            } else {
                failWriteEvent = responseTask;
                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * 建立与远程服务的连接会话,通过AioSession可进行数据传输
     *
     * @param remote
     * @return
     */
    public AioSession<Request> connect(SocketAddress remote) {
        return createAndCacheSession(remote);
    }

    /**
     * 创建并缓存与指定地址的会话信息
     *
     * @param remote
     * @return
     */
    UdpAioSession<Request> createAndCacheSession(final SocketAddress remote) {
        if (!(remote instanceof InetSocketAddress)) {
            throw new UnsupportedOperationException();

        }
        InetSocketAddress address = (InetSocketAddress) remote;
        String key = address.getHostName() + ":" + address.getPort();
        UdpAioSession<Request> session = udpAioSessionConcurrentHashMap.get(key);
        if (session != null) {
            return session;
        }
        synchronized (this) {
            if (session != null) {
                return session;
            }
            Function<WriteBuffer, Void> function = writeBuffer -> {
                VirtualBuffer virtualBuffer = writeBuffer.poll();
                if (virtualBuffer == null) {
                    return null;
                }
                try {
                    write(virtualBuffer, remote);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                return null;
            };
            IoServerConfig config = new IoServerConfig();
            config.setWriteQueueCapacity(1);
            config.setBufferPoolChunkSize(this.config.getBufferPoolChunkSize());
            WriteBuffer writeBuffer = new WriteBuffer(bufferPage, function, config);
            session = new UdpAioSession<>(this, remote, writeBuffer);
            udpAioSessionConcurrentHashMap.put(key, session);
        }
        return session;
    }

    /**
     * 关闭当前连接
     */
    public void close() {
        if (selectionKey != null) {
            Selector selector = selectionKey.selector();
            selectionKey.cancel();
            selector.wakeup();
            selectionKey = null;
        }
        for (Map.Entry<String, UdpAioSession<Request>> entry : udpAioSessionConcurrentHashMap.entrySet()) {
            entry.getValue().close();
        }
        try {
            if (channel != null) {
                channel.close();
                channel = null;
            }
        } catch (IOException e) {
            LOGGER.error("", e);
        }
        //内存回收
        ResponseTask task;
        while ((task = writeRingBuffer.poll()) != null) {
            task.response.clean();
        }
    }

    DatagramChannel getChannel() {
        return channel;
    }

    final class ResponseTask {
        /**
         * 待输出数据的接受地址
         */
        private SocketAddress remote;
        /**
         * 待输出数据
         */
        private VirtualBuffer response;

        public ResponseTask(SocketAddress remote, VirtualBuffer response) {
            this.remote = remote;
            this.response = response;
        }

    }
}
