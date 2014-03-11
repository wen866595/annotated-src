/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.util.concurrent.locks.*;

/**
 * A cancellable asynchronous computation. This class provides a base
 * implementation of {@link Future}, with methods to start and cancel a
 * computation, query to see if the computation is complete, and retrieve the
 * result of the computation. The result can only be retrieved when the
 * computation has completed; the <tt>get</tt> method will block if the
 * computation has not yet completed. Once the computation has completed, the
 * computation cannot be restarted or cancelled.
 * 
 * <p>
 * A <tt>FutureTask</tt> can be used to wrap a {@link Callable} or
 * {@link java.lang.Runnable} object. Because <tt>FutureTask</tt> implements
 * <tt>Runnable</tt>, a <tt>FutureTask</tt> can be submitted to an
 * {@link Executor} for execution.
 * 
 * <p>
 * In addition to serving as a standalone class, this class provides
 * <tt>protected</tt> functionality that may be useful when creating customized
 * task classes.
 * 
 * @since 1.5
 * @author Doug Lea
 * @param <V>
 *            The result type returned by this FutureTask's <tt>get</tt> method
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /** Synchronization control for FutureTask */
    // FutureTask 用于同步控制
    private final Sync sync;

    /**
     * Creates a <tt>FutureTask</tt> that will, upon running, execute the given
     * <tt>Callable</tt>.
     * 
     * @param callable
     *            the callable task
     * @throws NullPointerException
     *             if callable is null
     */
    public FutureTask(Callable<V> callable) {
	if (callable == null)
	    throw new NullPointerException();
	sync = new Sync(callable);
    }

    /**
     * Creates a <tt>FutureTask</tt> that will, upon running, execute the given
     * <tt>Runnable</tt>, and arrange that <tt>get</tt> will return the given
     * result on successful completion.
     * 
     * @param runnable
     *            the runnable task
     * @param result
     *            the result to return on successful completion. If you don't
     *            need a particular result, consider using constructions of the
     *            form:
     *            {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException
     *             if runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
	sync = new Sync(Executors.callable(runnable, result));
    }

    public boolean isCancelled() {
	return sync.innerIsCancelled();
    }

    public boolean isDone() {
	return sync.innerIsDone();
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
	return sync.innerCancel(mayInterruptIfRunning);
    }

    /**
     * @throws CancellationException
     *             {@inheritDoc}
     */
    public V get() throws InterruptedException, ExecutionException {
	return sync.innerGet();
    }

    /**
     * @throws CancellationException
     *             {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit) throws InterruptedException,
	    ExecutionException, TimeoutException {
	return sync.innerGet(unit.toNanos(timeout));
    }

    /**
     * Protected method invoked when this task transitions to state
     * <tt>isDone</tt> (whether normally or via cancellation). The default
     * implementation does nothing. Subclasses may override this method to
     * invoke completion callbacks or perform bookkeeping. Note that you can
     * query status inside the implementation of this method to determine
     * whether this task has been cancelled.
     */
    protected void done() {
    }

    /**
     * Sets the result of this Future to the given value unless this future has
     * already been set or has been cancelled. This method is invoked internally
     * by the <tt>run</tt> method upon successful completion of the computation.
     * 
     * @param v
     *            the value
     */
    protected void set(V v) {
	sync.innerSet(v);
    }

    /**
     * Causes this future to report an <tt>ExecutionException</tt> with the
     * given throwable as its cause, unless this Future has already been set or
     * has been cancelled. This method is invoked internally by the <tt>run</tt>
     * method upon failure of the computation.
     * 
     * @param t
     *            the cause of failure
     */
    protected void setException(Throwable t) {
	sync.innerSetException(t);
    }

    // The following (duplicated) doc comment can be removed once
    //
    // 6270645: Javadoc comments should be inherited from most derived
    // superinterface or superclass
    // is fixed.
    /**
     * Sets this Future to the result of its computation unless it has been
     * cancelled.
     */
    public void run() {
	sync.innerRun();
    }

    /**
     * Executes the computation without setting its result, and then resets this
     * Future to initial state, failing to do so if the computation encounters
     * an exception or is cancelled. This is designed for use with tasks that
     * intrinsically execute more than once.
     * 
     * @return true if successfully run and reset
     */
    protected boolean runAndReset() {
	return sync.innerRunAndReset();
    }

    /**
     * Synchronization control for FutureTask. Note that this must be a
     * non-static inner class in order to invoke the protected <tt>done</tt>
     * method. For clarity, all inner class support methods are same as outer,
     * prefixed with "inner".
     * 
     * Uses AQS sync state to represent run status
     */
    private final class Sync extends AbstractQueuedSynchronizer {
	private static final long serialVersionUID = -7828117401763700385L;

	// 定义表示任务执行状态的常量。由于使用了位运算进行判断，所以状态值分别是2的幂。

	/** State value representing that task is ready to run */
	// 表示任务已经准备好了，可以执行
	private static final int READY = 0;

	/** State value representing that task is running */
	// 表示任务正在执行中
	private static final int RUNNING = 1;

	/** State value representing that task ran */
	// 表示任务已执行完成
	private static final int RAN = 2;

	/** State value representing that task was cancelled */
	// 表示任务已取消
	private static final int CANCELLED = 4;

	/** The underlying callable */
	// 底层的表示任务的可执行对象
	private final Callable<V> callable;

	/** The result to return from get() */
	// 表示任务执行结果，用于get方法返回。
	private V result;

	/** The exception to throw from get() */
	// 表示任务执行中的异常，用于get方法调用时抛出。
	private Throwable exception;

	/**
	 * The thread running task. When nulled after set/cancel, this indicates
	 * that the results are accessible. Must be volatile, to ensure
	 * visibility upon completion.
	 */
	/*
	 * 用于执行任务的线程。在 set/cancel 方法后置为空，表示结果可获取。 必须是
	 * volatile的，用于确保完成后（result和exception）的可见性。
	 * （如果runner不是volatile，则result和exception必须都是volatile的）
	 */
	private volatile Thread runner;

	Sync(Callable<V> callable) {
	    this.callable = callable;
	}

	/**
	 * 检查任务是否处于完成或取消状态
	 */
	private boolean ranOrCancelled(int state) {
	    return (state & (RAN | CANCELLED)) != 0;
	}

	/**
	 * Implements AQS base acquire to succeed if ran or cancelled 已完成或已取消
	 * 时成功获取
	 */
	protected int tryAcquireShared(int ignore) {
	    return innerIsDone() ? 1 : -1;
	}

	/**
	 * Implements AQS base release to always signal after setting final done
	 * status by nulling runner thread. 在设置最终完成状态后让AQS总是通知，通过设置runner线程为空。
	 * 这个方法并没有更新AQS的state属性， 所以可见性是通过对volatile的runner的写来保证的。
	 */
	protected boolean tryReleaseShared(int ignore) {
	    runner = null;
	    return true;
	}

	boolean innerIsCancelled() {
	    return getState() == CANCELLED;
	}

	boolean innerIsDone() {
	    return ranOrCancelled(getState()) && runner == null;
	}

	// 获取异步计算的结果
	V innerGet() throws InterruptedException, ExecutionException {
	    acquireSharedInterruptibly(0); // 获取共享，如果没有完成则会阻塞。

	    // 检查是否被取消
	    if (getState() == CANCELLED)
		throw new CancellationException();

	    // 异步计算过程中出现异常
	    if (exception != null)
		throw new ExecutionException(exception);

	    return result;
	}

	V innerGet(long nanosTimeout) throws InterruptedException,
		ExecutionException, TimeoutException {
	    if (!tryAcquireSharedNanos(0, nanosTimeout))
		throw new TimeoutException();
	    if (getState() == CANCELLED)
		throw new CancellationException();
	    if (exception != null)
		throw new ExecutionException(exception);
	    return result;
	}

	// 设置结果
	void innerSet(V v) {
	    // 放在循环里进行是为了失败后重试。
	    for (;;) {
		int s = getState(); // AQS初始化时，状态值默认是 0，对应这里也就是 READY 状态。

		// 已完成任务不能设置结果
		if (s == RAN)
		    return;

		// 已取消 的任务不能设置结果
		if (s == CANCELLED) {
		    // releaseShared 会设置runner为空，
		    // 这是考虑到与其他的取消请求线程 竞争中断 runner
		    // aggressively release to set runner to null,
		    // in case we are racing with a cancel request
		    // that will try to interrupt runner
		    releaseShared(0);
		    return;
		}

		// 先设置已完成，免得多次设置
		if (compareAndSetState(s, RAN)) {
		    result = v;
		    releaseShared(0); // 此方法会更新 runner，保证result的可见性
		    done();
		    return;
		}
	    }
	}

	void innerSetException(Throwable t) {
	    for (;;) {
		int s = getState();
		if (s == RAN)
		    return;
		if (s == CANCELLED) {
		    // aggressively release to set runner to null,
		    // in case we are racing with a cancel request
		    // that will try to interrupt runner
		    releaseShared(0);
		    return;
		}
		if (compareAndSetState(s, RAN)) {
		    exception = t;
		    releaseShared(0);
		    done();
		    return;
		}
	    }
	}

	boolean innerCancel(boolean mayInterruptIfRunning) {
	    for (;;) {
		int s = getState();
		if (ranOrCancelled(s)) // 已完成或已取消的任务不能再次取消
		    return false;
		if (compareAndSetState(s, CANCELLED)) // 任务处于 READY 或 RUNNING
		    break;
	    }
	    // 任务取消后，中断执行线程
	    if (mayInterruptIfRunning) {
		Thread r = runner;
		if (r != null)
		    r.interrupt();
	    }
	    releaseShared(0);// 释放等待的访问结果的线程
	    done();
	    return true;
	}

	void innerRun() {
	    // 用于确保任务不会重复执行
	    if (!compareAndSetState(READY, RUNNING))
		return;

	    // 由于Future一般是异步执行，所以runner一般是线程池里的线程。
	    runner = Thread.currentThread();

	    // 设置执行线程后再次检查，在执行前检查是否被异步取消
	    // 由于前面的CAS已把状态设置RUNNING，
	    if (getState() == RUNNING) { // recheck after setting thread
		V result;
		//
		try {
		    result = callable.call();
		} catch (Throwable ex) {
		    // 捕获任务执行过程中抛出的所有异常
		    setException(ex);
		    return;
		}
		set(result);
	    } else {
		// 释放等待的线程
		releaseShared(0); // cancel
	    }
	}

	boolean innerRunAndReset() {
	    if (!compareAndSetState(READY, RUNNING))
		return false;
	    try {
		runner = Thread.currentThread();
		if (getState() == RUNNING)
		    callable.call(); // don't set result
		runner = null;
		return compareAndSetState(RUNNING, READY);
	    } catch (Throwable ex) {
		setException(ex);
		return false;
	    }
	}
    }
}
