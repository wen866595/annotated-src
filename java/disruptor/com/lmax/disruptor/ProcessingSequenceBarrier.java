/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;

/**
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a
 * cursor sequence and optional dependent {@link EventProcessor}(s), using the
 * given WaitStrategy.
 */
final class ProcessingSequenceBarrier implements SequenceBarrier {
    private final WaitStrategy waitStrategy;
    private final Sequence dependentSequence;
    private volatile boolean alerted = false;
    private final Sequence cursorSequence;
    private final Sequencer sequencer;

    /**
     * @param sequencer 生产者序号控制器
     * @param waitStrategy 等待策略
     * @param cursorSequence 生产者序号
     * @param dependentSequences 依赖的Sequence
     */
    public ProcessingSequenceBarrier(final Sequencer sequencer, final WaitStrategy waitStrategy,
                                     final Sequence cursorSequence, final Sequence[] dependentSequences) {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;

        // 如果事件处理器不依赖于任何前置处理器，那么dependentSequence也指向生产者的序号。
        if (0 == dependentSequences.length) {
            dependentSequence = cursorSequence;
        } else {
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    /**
     * 该方法不保证总是返回未处理的序号；如果有更多的可处理序号时，返回的序号也可能是超过指定序号的。
     */
    public long waitFor(final long sequence) throws AlertException, InterruptedException, TimeoutException {
        // 首先检查有无通知
        checkAlert();

        // 通过等待策略来获取可处理事件序号，
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);

        // 这个方法不保证总是返回可处理的序号
        if (availableSequence < sequence) {
            return availableSequence;
        }

        // 再通过生产者序号控制器返回最大的可处理序号
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor()
    {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
        return alerted;
    }

    @Override
    public void alert()
    {
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}