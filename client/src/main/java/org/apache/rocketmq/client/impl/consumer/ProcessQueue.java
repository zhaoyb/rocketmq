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
package org.apache.rocketmq.client.impl.consumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.log.ClientLogger;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.body.ProcessQueueInfo;

/**
 * Queue consumption snapshot
 */
public class ProcessQueue {
    public final static long REBALANCE_LOCK_MAX_LIVE_TIME =
        Long.parseLong(System.getProperty("rocketmq.client.rebalance.lockMaxLiveTime", "30000"));
    public final static long REBALANCE_LOCK_INTERVAL = Long.parseLong(System.getProperty("rocketmq.client.rebalance.lockInterval", "20000"));
    private final static long PULL_MAX_IDLE_TIME = Long.parseLong(System.getProperty("rocketmq.client.pull.pullMaxIdleTime", "120000"));
    private final InternalLogger log = ClientLogger.getLog();
    private final ReadWriteLock lockTreeMap = new ReentrantReadWriteLock();
    private final TreeMap<Long, MessageExt> msgTreeMap = new TreeMap<Long, MessageExt>();
    private final AtomicLong msgCount = new AtomicLong();
    private final AtomicLong msgSize = new AtomicLong();
    private final Lock lockConsume = new ReentrantLock();
    /**
     * A subset of msgTreeMap, will only be used when orderly consume
     */
    private final TreeMap<Long, MessageExt> consumingMsgOrderlyTreeMap = new TreeMap<Long, MessageExt>();
    private final AtomicLong tryUnlockTimes = new AtomicLong(0);
    private volatile long queueOffsetMax = 0L;
    private volatile boolean dropped = false;
    private volatile long lastPullTimestamp = System.currentTimeMillis();
    private volatile long lastConsumeTimestamp = System.currentTimeMillis();
    private volatile boolean locked = false;
    private volatile long lastLockTimestamp = System.currentTimeMillis();
    private volatile boolean consuming = false;
    // 消息积压数量
    private volatile long msgAccCnt = 0;

    public boolean isLockExpired() {
        return (System.currentTimeMillis() - this.lastLockTimestamp) > REBALANCE_LOCK_MAX_LIVE_TIME;
    }

    public boolean isPullExpired() {
        return (System.currentTimeMillis() - this.lastPullTimestamp) > PULL_MAX_IDLE_TIME;
    }

    /**
     *
     * 清空到期消息
     *
     * @param pushConsumer
     */
    public void cleanExpiredMsg(DefaultMQPushConsumer pushConsumer) {
        if (pushConsumer.getDefaultMQPushConsumerImpl().isConsumeOrderly()) {
            return;
        }

        int loop = msgTreeMap.size() < 16 ? msgTreeMap.size() : 16;
        for (int i = 0; i < loop; i++) {
            MessageExt msg = null;
            try {
                this.lockTreeMap.readLock().lockInterruptibly();
                try {
                    if (!msgTreeMap.isEmpty() && System.currentTimeMillis() - Long.parseLong(MessageAccessor.getConsumeStartTimeStamp(msgTreeMap.firstEntry().getValue())) > pushConsumer.getConsumeTimeout() * 60 * 1000) {
                        msg = msgTreeMap.firstEntry().getValue();
                    } else {

                        break;
                    }
                } finally {
                    this.lockTreeMap.readLock().unlock();
                }
            } catch (InterruptedException e) {
                log.error("getExpiredMsg exception", e);
            }

            try {

                pushConsumer.sendMessageBack(msg, 3);
                log.info("send expire msg back. topic={}, msgId={}, storeHost={}, queueId={}, queueOffset={}", msg.getTopic(), msg.getMsgId(), msg.getStoreHost(), msg.getQueueId(), msg.getQueueOffset());
                try {
                    this.lockTreeMap.writeLock().lockInterruptibly();
                    try {
                        if (!msgTreeMap.isEmpty() && msg.getQueueOffset() == msgTreeMap.firstKey()) {
                            try {
                                removeMessage(Collections.singletonList(msg));
                            } catch (Exception e) {
                                log.error("send expired msg exception", e);
                            }
                        }
                    } finally {
                        this.lockTreeMap.writeLock().unlock();
                    }
                } catch (InterruptedException e) {
                    log.error("getExpiredMsg exception", e);
                }
            } catch (Exception e) {
                log.error("send expired msg exception", e);
            }
        }
    }

    /**
     *
     * 将消息加入到msgTreeMap， 是一个TreeMap结构
     *
     * @param msgs
     * @return
     */
    public boolean putMessage(final List<MessageExt> msgs) {
        boolean dispatchToConsume = false;
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                int validMsgCnt = 0;
                for (MessageExt msg : msgs) {
                    // 放入treemap,
                    MessageExt old = msgTreeMap.put(msg.getQueueOffset(), msg);
                    if (null == old) {
                        validMsgCnt++;
                        // 最大offset
                        this.queueOffsetMax = msg.getQueueOffset();
                        // 消息Size累加
                        msgSize.addAndGet(msg.getBody().length);
                    }
                }
                //消息数量累加
                msgCount.addAndGet(validMsgCnt);

                if (!msgTreeMap.isEmpty() && !this.consuming) {
                    dispatchToConsume = true;
                    this.consuming = true;
                }

                if (!msgs.isEmpty()) {
                    // 最后一条消息
                    MessageExt messageExt = msgs.get(msgs.size() - 1);
                    // 从最后一条消息中取出 max offset
                    String property = messageExt.getProperty(MessageConst.PROPERTY_MAX_OFFSET);
                    if (property != null) {
                        // 最大offset 减去 最后一条消息offset
                        long accTotal = Long.parseLong(property) - messageExt.getQueueOffset();
                        if (accTotal > 0) {
                            // 消息积压数量
                            this.msgAccCnt = accTotal;
                        }
                    }
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("putMessage exception", e);
        }

        return dispatchToConsume;
    }

    /**
     * 消息最大跨度，即 最大offset 减去 最小offset
     *
     * @return
     */
    public long getMaxSpan() {
        try {
            this.lockTreeMap.readLock().lockInterruptibly();
            try {
                if (!this.msgTreeMap.isEmpty()) {
                    return this.msgTreeMap.lastKey() - this.msgTreeMap.firstKey();
                }
            } finally {
                this.lockTreeMap.readLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("getMaxSpan exception", e);
        }

        return 0;
    }

    /**
     * 移除指定的消息
     *
     */
    public long removeMessage(final List<MessageExt> msgs) {
        long result = -1;
        final long now = System.currentTimeMillis();
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            this.lastConsumeTimestamp = now;
            try {
                if (!msgTreeMap.isEmpty()) {
                    result = this.queueOffsetMax + 1;
                    int removedCnt = 0;
                    for (MessageExt msg : msgs) {
                        MessageExt prev = msgTreeMap.remove(msg.getQueueOffset());
                        if (prev != null) {
                            removedCnt--;
                            msgSize.addAndGet(0 - msg.getBody().length);
                        }
                    }
                    msgCount.addAndGet(removedCnt);

                    if (!msgTreeMap.isEmpty()) {
                        result = msgTreeMap.firstKey();
                    }
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (Throwable t) {
            log.error("removeMessage exception", t);
        }

        return result;
    }

    public TreeMap<Long, MessageExt> getMsgTreeMap() {
        return msgTreeMap;
    }

    public AtomicLong getMsgCount() {
        return msgCount;
    }

    public AtomicLong getMsgSize() {
        return msgSize;
    }

    public boolean isDropped() {
        return dropped;
    }

    public void setDropped(boolean dropped) {
        this.dropped = dropped;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /**
     *
     * 回滚操作，即把consumingMsgOrderlyTreeMap重新放回msgTreeMap
     *
     */
    public void rollback() {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                this.msgTreeMap.putAll(this.consumingMsgOrderlyTreeMap);
                this.consumingMsgOrderlyTreeMap.clear();
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("rollback exception", e);
        }
    }

    /**提交
     *
     * consumingMsgOrderlyTreeMap 设置为空
     *
     * @return
     */
    public long commit() {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                Long offset = this.consumingMsgOrderlyTreeMap.lastKey();
                msgCount.addAndGet(0 - this.consumingMsgOrderlyTreeMap.size());
                for (MessageExt msg : this.consumingMsgOrderlyTreeMap.values()) {
                    msgSize.addAndGet(0 - msg.getBody().length);
                }
                this.consumingMsgOrderlyTreeMap.clear();
                if (offset != null) {
                    return offset + 1;
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("commit exception", e);
        }

        return -1;
    }

    /**
     *
     * 重新消费
     *
     * @param msgs
     */
    public void makeMessageToCosumeAgain(List<MessageExt> msgs) {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                for (MessageExt msg : msgs) {
                    this.consumingMsgOrderlyTreeMap.remove(msg.getQueueOffset());
                    this.msgTreeMap.put(msg.getQueueOffset(), msg);
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("makeMessageToCosumeAgain exception", e);
        }
    }

    /**
     * 提取消息
     *
     * @param batchSize
     * @return
     */
    public List<MessageExt> takeMessags(final int batchSize) {
        List<MessageExt> result = new ArrayList<MessageExt>(batchSize);
        final long now = System.currentTimeMillis();
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            this.lastConsumeTimestamp = now;
            try {
                if (!this.msgTreeMap.isEmpty()) {
                    for (int i = 0; i < batchSize; i++) {
                        // 获取消息， firstEntry表示最小offset消息，并且删除
                        Map.Entry<Long, MessageExt> entry = this.msgTreeMap.pollFirstEntry();
                        if (entry != null) {
                            result.add(entry.getValue());
                            // 有序队列，注意本地提取的消息，被放入到了consumingMsgOrderlyTreeMap
                            consumingMsgOrderlyTreeMap.put(entry.getKey(), entry.getValue());
                        } else {
                            break;
                        }
                    }
                }

                if (result.isEmpty()) {
                    consuming = false;
                }
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("take Messages exception", e);
        }

        return result;
    }

    /**
     * 是否还有消息
     */
    public boolean hasTempMessage() {
        try {
            this.lockTreeMap.readLock().lockInterruptibly();
            try {
                return !this.msgTreeMap.isEmpty();
            } finally {
                this.lockTreeMap.readLock().unlock();
            }
        } catch (InterruptedException e) {
        }

        return true;
    }

    /**
     * 清空消息
     *
     */
    public void clear() {
        try {
            this.lockTreeMap.writeLock().lockInterruptibly();
            try {
                this.msgTreeMap.clear();
                this.consumingMsgOrderlyTreeMap.clear();
                this.msgCount.set(0);
                this.msgSize.set(0);
                this.queueOffsetMax = 0L;
            } finally {
                this.lockTreeMap.writeLock().unlock();
            }
        } catch (InterruptedException e) {
            log.error("rollback exception", e);
        }
    }

    public long getLastLockTimestamp() {
        return lastLockTimestamp;
    }

    public void setLastLockTimestamp(long lastLockTimestamp) {
        this.lastLockTimestamp = lastLockTimestamp;
    }

    public Lock getLockConsume() {
        return lockConsume;
    }

    public long getLastPullTimestamp() {
        return lastPullTimestamp;
    }

    public void setLastPullTimestamp(long lastPullTimestamp) {
        this.lastPullTimestamp = lastPullTimestamp;
    }

    public long getMsgAccCnt() {
        return msgAccCnt;
    }

    public void setMsgAccCnt(long msgAccCnt) {
        this.msgAccCnt = msgAccCnt;
    }

    public long getTryUnlockTimes() {
        return this.tryUnlockTimes.get();
    }

    public void incTryUnlockTimes() {
        this.tryUnlockTimes.incrementAndGet();
    }

    /**
     *  填充信息
     *
     * @param info
     */
    public void fillProcessQueueInfo(final ProcessQueueInfo info) {
        try {
            this.lockTreeMap.readLock().lockInterruptibly();

            if (!this.msgTreeMap.isEmpty()) {
                info.setCachedMsgMinOffset(this.msgTreeMap.firstKey());
                info.setCachedMsgMaxOffset(this.msgTreeMap.lastKey());
                info.setCachedMsgCount(this.msgTreeMap.size());
                info.setCachedMsgSizeInMiB((int) (this.msgSize.get() / (1024 * 1024)));
            }

            if (!this.consumingMsgOrderlyTreeMap.isEmpty()) {
                info.setTransactionMsgMinOffset(this.consumingMsgOrderlyTreeMap.firstKey());
                info.setTransactionMsgMaxOffset(this.consumingMsgOrderlyTreeMap.lastKey());
                info.setTransactionMsgCount(this.consumingMsgOrderlyTreeMap.size());
            }

            info.setLocked(this.locked);
            info.setTryUnlockTimes(this.tryUnlockTimes.get());
            info.setLastLockTimestamp(this.lastLockTimestamp);

            info.setDroped(this.dropped);
            info.setLastPullTimestamp(this.lastPullTimestamp);
            info.setLastConsumeTimestamp(this.lastConsumeTimestamp);
        } catch (Exception e) {
        } finally {
            this.lockTreeMap.readLock().unlock();
        }
    }

    public long getLastConsumeTimestamp() {
        return lastConsumeTimestamp;
    }

    public void setLastConsumeTimestamp(long lastConsumeTimestamp) {
        this.lastConsumeTimestamp = lastConsumeTimestamp;
    }

}
