/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker;

import java.util.concurrent.atomic.AtomicReference;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.util.AssertUtil;
import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.util.function.BiConsumer;

/**
 * @author Eric Zhao
 * @since 1.8.0
 */
public abstract class AbstractCircuitBreaker implements CircuitBreaker {

    protected final DegradeRule rule;
    protected final int recoveryTimeoutMs;

    private final EventObserverRegistry observerRegistry;

    protected final AtomicReference<State> currentState = new AtomicReference<>(State.CLOSED);
    protected volatile long nextRetryTimestamp;

    public AbstractCircuitBreaker(DegradeRule rule) {
        this(rule, EventObserverRegistry.getInstance());
    }

    AbstractCircuitBreaker(DegradeRule rule, EventObserverRegistry observerRegistry) {
        AssertUtil.notNull(observerRegistry, "observerRegistry cannot be null");
        if (!DegradeRuleManager.isValidRule(rule)) {
            throw new IllegalArgumentException("Invalid DegradeRule: " + rule);
        }
        this.observerRegistry = observerRegistry;
        this.rule = rule;
        this.recoveryTimeoutMs = rule.getTimeWindow() * 1000;
    }

    @Override
    public DegradeRule getRule() {
        return rule;
    }

    @Override
    public State currentState() {
        return currentState.get();
    }

    @Override
    public boolean tryPass(Context context) {
        // 判断状态机状态
        if (currentState.get() == State.CLOSED) {
            // 如果是 close 状态  直接放行
            return true;
        }
        if (currentState.get() == State.OPEN) {
            // 如果是 open 状态,断路器打开
            // 继续判断 open 时间窗是否结束, 如果是则把状态从 open 切换到 half_open
            return retryTimeoutArrived() && fromOpenToHalfOpen(context);
        }
        return false;
    }

    /**
     * Reset the statistic data.
     */
    abstract void resetStat();

    protected boolean retryTimeoutArrived() {
        // 当前时间 > 下一次 halfOpen 的重试时间
        return TimeUtil.currentTimeMillis() >= nextRetryTimestamp;
    }

    protected void updateNextRetryTimestamp() {
        this.nextRetryTimestamp = TimeUtil.currentTimeMillis() + recoveryTimeoutMs;
    }

    protected boolean fromCloseToOpen(double snapshotValue) {
        State prev = State.CLOSED;
        if (currentState.compareAndSet(prev, State.OPEN)) {
            updateNextRetryTimestamp();

            notifyObservers(prev, State.OPEN, snapshotValue);
            return true;
        }
        return false;
    }

    protected boolean fromOpenToHalfOpen(Context context) {
        // 基于 cas 修改状态 从 open 到 half_open
        if (currentState.compareAndSet(State.OPEN, State.HALF_OPEN)) {
            // 状态变更的事件通知
            notifyObservers(State.OPEN, State.HALF_OPEN, null);
            Entry entry = context.getCurEntry();
            // 给资源设置监听器，在资源 Entry 销毁时(资源业务执行完毕时)触发
            entry.whenTerminate((context1, entry1) -> {
                if (entry1.getBlockError() != null) {
                    // 如果异常, 则再次进入 open 状态
                    currentState.compareAndSet(State.HALF_OPEN, State.OPEN);
                    notifyObservers(State.HALF_OPEN, State.OPEN, 1.0d);
                }
            });
            return true;
        }
        return false;
    }
    
    private void notifyObservers(CircuitBreaker.State prevState, CircuitBreaker.State newState, Double snapshotValue) {
        for (CircuitBreakerStateChangeObserver observer : observerRegistry.getStateChangeObservers()) {
            observer.onStateChange(prevState, newState, rule, snapshotValue);
        }
    }

    protected boolean fromHalfOpenToOpen(double snapshotValue) {
        if (currentState.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            updateNextRetryTimestamp();
            notifyObservers(State.HALF_OPEN, State.OPEN, snapshotValue);
            return true;
        }
        return false;
    }

    protected boolean fromHalfOpenToClose() {
        if (currentState.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            resetStat();
            notifyObservers(State.HALF_OPEN, State.CLOSED, null);
            return true;
        }
        return false;
    }

    protected void transformToOpen(double triggerValue) {
        State cs = currentState.get();
        switch (cs) {
            case CLOSED:
                fromCloseToOpen(triggerValue);
                break;
            case HALF_OPEN:
                fromHalfOpenToOpen(triggerValue);
                break;
            default:
                break;
        }
    }
}
