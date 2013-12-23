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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Convenience class for handling the batching semantics of consuming entries
 * from a {@link RingBuffer} and delegating the available events to an
 * {@link EventHandler}.
 *
 * If the {@link EventHandler} also implements {@link LifecycleAware} it will be
 * notified just after the thread is started and just before the thread is
 * shutdown.
 *
 * @param <T>
 *            event implementation storing the data for sharing during exchange
 *            or parallel coordination of an event.
 */
public final class BatchEventProcessor<T> implements EventProcessor
{
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExceptionHandler exceptionHandler = new FatalExceptionHandler();
    private final DataProvider<T> dataProvider;
    private final SequenceBarrier sequenceBarrier;
    private final EventHandler<T> eventHandler;
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final TimeoutHandler timeoutHandler;

    /**
     * Construct a {@link EventProcessor} that will automatically track the
     * progress by updating its sequence when the
     * {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider
     *            to which events are published.
     * @param sequenceBarrier
     *            on which it is waiting.
     * @param eventHandler
     *            is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(final DataProvider<T> dataProvider, final SequenceBarrier sequenceBarrier,
                               final EventHandler<T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        timeoutHandler = (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    @Override
    public void halt()
    {
        running.set(false);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get();
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out
     * of the {@link BatchEventProcessor}
     *
     * @param exceptionHandler
     *            to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException
     *             if this object instance is already running in a thread
     */
    @Override
public void run() {
    // 确保一次只有一个线程执行此方法，这样访问自身的序号就不要加锁
    if (!running.compareAndSet(false, true)) {
        throw new IllegalStateException("Thread is already running");
    }
    sequenceBarrier.clearAlert();   // 清除前置序号关卡的通知状态

    notifyStart();  // 声明周期通知，开始前回调

    T event = null;
    long nextSequence = sequence.get() + 1L;    // sequence指向上一个已处理的事件，默认是-1.
    try {
        while (true) {
            try {
                // 从它的前置序号关卡获取下一个可处理的事件序号。
                // 如果这个事件处理器不依赖于其他的事件处理器，则前置关卡就是生产者序号；
                // 如果这个事件处理器依赖于1个或多个事件处理器，那么这个前置关卡就是这些前置事件处理器中最慢的一个。
                // 通过这样，可以确保事件处理器不会超前处理地事件。
                final long availableSequence = sequenceBarrier.waitFor(nextSequence);

                // 处理一批事件
                while (nextSequence <= availableSequence) {
                    event = dataProvider.get(nextSequence);
                    eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                    nextSequence++;
                }

                // 设置它自己最后处理的事件序号，这样依赖于它的处理器可以处理它刚处理过的事件。
                sequence.set(availableSequence);
            } catch (final TimeoutException e) {
                // 获取事件序号超时处理
                notifyTimeout(sequence.get());

            } catch (final AlertException ex) {
                // 处理通知事件；检测是否要停止，如果非则继续处理事件
                if (!running.get()) {
                    break;
                }
            } catch (final Throwable ex) {
                // 其他异常，用事件处理器处理；然后继续处理下一个事件
                exceptionHandler.handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    } finally {
        // 声明周期通知，停止事件回调；复位运行状态标志，确保可以再次运行此方法。
        notifyShutdown();
        running.set(false);
    }
}

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            exceptionHandler.handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting
     * down
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                exceptionHandler.handleOnShutdownException(ex);
            }
        }
    }
}