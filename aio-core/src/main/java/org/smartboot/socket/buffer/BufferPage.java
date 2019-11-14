package org.smartboot.socket.buffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ByteBuffer内存页
 *
 * @author 三刀
 * @version V1.0 , 2018/10/31
 */
public final class BufferPage {
    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(BufferPage.class);

    /**
     * 当前空闲的虚拟Buffer
     */
    private List<VirtualBuffer> availableBuffers;
    /**
     * 待回收的虚拟Buffer
     */
    private ConcurrentLinkedQueue<VirtualBuffer> cleanBuffers = new ConcurrentLinkedQueue<>();

    /**
     * 当前缓存页的物理缓冲区
     */
    private ByteBuffer buffer;
    /**
     * 条件锁
     */
    private ReentrantLock lock = new ReentrantLock();

    /**
     * 内存页是否处于空闲状态
     */
    private boolean idle = true;

    /**
     * @param size   缓存页大小
     * @param direct 是否使用堆外内存
     */
    BufferPage(int size, boolean direct) {
        availableBuffers = new LinkedList<>();
        this.buffer = allocate0(size, direct);
        availableBuffers.add(new VirtualBuffer(this, null, buffer.position(), buffer.limit()));
    }

    /**
     * 申请物理内存页空间
     *
     * @param size   物理空间大小
     * @param direct true:堆外缓冲区,false:堆内缓冲区
     * @return 缓冲区
     */
    private ByteBuffer allocate0(int size, boolean direct) {
        return direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
    }

    /**
     * 申请虚拟内存
     *
     * @param size 申请大小
     * @return 虚拟内存对象
     */
    public VirtualBuffer allocate(final int size) {
        idle = false;
        VirtualBuffer cleanBuffer = cleanBuffers.poll();
        if (cleanBuffer != null && cleanBuffer.getParentLimit() - cleanBuffer.getParentPosition() >= size) {
            cleanBuffer.buffer().clear();
            cleanBuffer.buffer(cleanBuffer.buffer());
            return cleanBuffer;
        }
        lock.lock();
        try {
            if (cleanBuffer != null) {
                clean0(cleanBuffer);
            }
            while ((cleanBuffer = cleanBuffers.poll()) != null) {
                if (cleanBuffer.getParentLimit() - cleanBuffer.getParentPosition() >= size) {
                    cleanBuffer.buffer().clear();
                    cleanBuffer.buffer(cleanBuffer.buffer());
                    return cleanBuffer;
                } else {
                    clean0(cleanBuffer);
                }
            }
            int count = availableBuffers.size();
            VirtualBuffer bufferChunk = null;
            if (count == 1) {
                bufferChunk = fastAllocate(size);
            } else if (count > 1) {
                bufferChunk = slowAllocate(size);
            }
            if (bufferChunk != null) {
                return bufferChunk;
            }
        } finally {
            lock.unlock();
        }
//        if(LOGGER.isDebugEnabled()) {
        LOGGER.warn("bufferPage has no available space: " + size);
//        }
        return new VirtualBuffer(null, allocate0(size, false), 0, 0);

    }

    /**
     * 快速匹配
     *
     * @param size
     * @return
     */
    private VirtualBuffer fastAllocate(int size) {
        VirtualBuffer freeChunk = availableBuffers.get(0);
        final int remaining = freeChunk.getParentLimit() - freeChunk.getParentPosition();
        if (remaining < size) {
            return null;
        }
        VirtualBuffer bufferChunk;
        if (remaining == size) {
            availableBuffers.clear();
            buffer.limit(freeChunk.getParentLimit());
            buffer.position(freeChunk.getParentPosition());
            freeChunk.buffer(buffer.slice());
            bufferChunk = freeChunk;
        } else {
            buffer.limit(freeChunk.getParentPosition() + size);
            buffer.position(freeChunk.getParentPosition());
            bufferChunk = new VirtualBuffer(this, buffer.slice(), buffer.position(), buffer.limit());
            freeChunk.setParentPosition(buffer.limit());
        }
        if (bufferChunk.buffer().remaining() != size) {
            throw new RuntimeException("allocate " + size + ", buffer:" + bufferChunk);
        }
        return bufferChunk;
    }

    /**
     * 迭代申请
     *
     * @param size
     * @return
     */
    private VirtualBuffer slowAllocate(int size) {
        Iterator<VirtualBuffer> iterator = availableBuffers.iterator();
        VirtualBuffer bufferChunk = null;
        while (iterator.hasNext()) {
            VirtualBuffer freeChunk = iterator.next();
            final int remaining = freeChunk.getParentLimit() - freeChunk.getParentPosition();
            if (remaining < size) {
                continue;
            }
            if (remaining == size) {
                iterator.remove();
                buffer.limit(freeChunk.getParentLimit());
                buffer.position(freeChunk.getParentPosition());
                freeChunk.buffer(buffer.slice());
                bufferChunk = freeChunk;
            } else {
                buffer.limit(freeChunk.getParentPosition() + size);
                buffer.position(freeChunk.getParentPosition());
                bufferChunk = new VirtualBuffer(this, buffer.slice(), buffer.position(), buffer.limit());
                freeChunk.setParentPosition(buffer.limit());
            }
            if (bufferChunk.buffer().remaining() != size) {
                throw new RuntimeException("allocate " + size + ", buffer:" + bufferChunk);
            }
            break;
        }
        return bufferChunk;
    }

    /**
     * 内存回收
     *
     * @param cleanBuffer 待回收的虚拟内存
     */
    void clean(VirtualBuffer cleanBuffer) {
        cleanBuffers.offer(cleanBuffer);
    }

    /**
     * 尝试回收缓冲区
     */
    void tryClean() {
        //下个周期依旧处于空闲则触发回收任务
        if (!idle) {
            idle = true;
        } else if (lock.tryLock()) {
            try {
                VirtualBuffer cleanBuffer;
                while ((cleanBuffer = cleanBuffers.poll()) != null) {
                    clean0(cleanBuffer);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 回收虚拟缓冲区
     *
     * @param cleanBuffer 虚拟缓冲区
     */
    private void clean0(VirtualBuffer cleanBuffer) {
        int index = 0;
        Iterator<VirtualBuffer> iterator = availableBuffers.iterator();
        while (iterator.hasNext()) {
            VirtualBuffer freeBuffer = iterator.next();
            //cleanBuffer在freeBuffer之前并且形成连续块
            if (freeBuffer.getParentPosition() == cleanBuffer.getParentLimit()) {
                freeBuffer.setParentPosition(cleanBuffer.getParentPosition());
                return;
            }
            //cleanBuffer与freeBuffer之后并形成连续块
            if (freeBuffer.getParentLimit() == cleanBuffer.getParentPosition()) {
                freeBuffer.setParentLimit(cleanBuffer.getParentLimit());
                //判断后一个是否连续
                if (iterator.hasNext()) {
                    VirtualBuffer next = iterator.next();
                    if (next.getParentPosition() == freeBuffer.getParentLimit()) {
                        freeBuffer.setParentLimit(next.getParentLimit());
                        iterator.remove();
                    } else if (next.getParentPosition() < freeBuffer.getParentLimit()) {
                        throw new IllegalStateException("");
                    }
                }
                return;
            }
            if (freeBuffer.getParentPosition() > cleanBuffer.getParentLimit()) {
                availableBuffers.add(index, cleanBuffer);
                return;
            }
            index++;
        }
        availableBuffers.add(cleanBuffer);
    }

    @Override
    public String toString() {
        return "BufferPage{availableBuffers=" + availableBuffers + ", cleanBuffers=" + cleanBuffers + '}';
    }
}
